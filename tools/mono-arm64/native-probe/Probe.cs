using System;
using System.Runtime.CompilerServices;
using System.Threading;

namespace RimDroid.MonoArm64Probe
{
    public static class EntryPoint
    {
        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern int NativeAdd(int left, int right);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern long NativeAddLong(long left, long right);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern IntPtr NativePointerIdentity(IntPtr value);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern double NativeAddDouble(double left, double right);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern double NativeAddFloatArgs(float left, float right);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern long NativeSumNine(
            long a, long b, long c, long d, long e,
            long f, long g, long h, long i);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern sbyte NativeSByteIdentity(sbyte value);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern byte NativeByteIdentity(byte value);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern short NativeInt16Identity(short value);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern ushort NativeUInt16Identity(ushort value);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern void NativePublishObject(object value);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern void NativeInstallGuestRoot();

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern void NativeClearGuestRoot();

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern int NativeThrowFromGuest(int marker);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern int NativeBenchIdentity(int value);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern long NativeNowNs();

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern void NativeReportBench(long reverseIcallNs, long managedCallNs, int calls);

        [MethodImpl(MethodImplOptions.InternalCall)]
        private static extern int NativeSmcRound(int round);

        private sealed class NullTarget
        {
            public int Value = 1;
        }

        [MethodImpl(MethodImplOptions.NoInlining)]
        private static int ReadThroughNull(NullTarget target)
        {
            return target.Value;
        }

        public static int RunBasic()
        {
            return 0x5244;
        }

        public static int RunReverseIcall()
        {
            return NativeAdd(0x5200, 0x44);
        }

        public static int RunAbiMatrix()
        {
            if (NativeAddLong(0x1234567800000000L, 0x44L) != 0x1234567800000044L)
                return -101;

            IntPtr pointer = new IntPtr(unchecked((long)0x123456789abcdef0UL));
            if (NativePointerIdentity(pointer) != pointer)
                return -102;

            if (Math.Abs(NativeAddDouble(1.25, 2.5) - 3.75) > 0.000001)
                return -103;

            if (Math.Abs(NativeAddFloatArgs(1.25f, 2.5f) - 3.75) > 0.000001)
                return -104;

            if (NativeSumNine(1, 2, 3, 4, 5, 6, 7, 8, 9) != 45)
                return -105;

            if (NativeSByteIdentity(-101) != -101)
                return -106;

            if (NativeByteIdentity(0xd3) != 0xd3)
                return -107;

            if (NativeInt16Identity(-12345) != -12345)
                return -108;

            if (NativeUInt16Identity(54321) != 54321)
                return -109;

            return 0x5244;
        }

        public static int RunStress()
        {
            const int count = 4096;
            byte[][] blocks = new byte[count][];
            long expected = 0;
            long observed = 0;

            for (int i = 0; i < blocks.Length; i++)
            {
                blocks[i] = new byte[1024 + (i & 127)];
                blocks[i][0] = (byte)i;
                expected += blocks[i][0];
            }

            Thread worker = new Thread(() =>
            {
                long sum = 0;
                for (int i = 0; i < blocks.Length; i++)
                    sum += blocks[i][0];
                Interlocked.Exchange(ref observed, sum);
            });
            worker.Start();
            worker.Join();

            GC.Collect();
            GC.WaitForPendingFinalizers();
            GC.Collect();

            return observed == expected ? 0x5244 : -1;
        }

        // Boehm scans native stacks and saved registers conservatively. Publishing the object on the
        // thread that later collects leaves its raw address behind in dead native frames of the Box64
        // reverse-icall path, which kept BOTH the rooted object and the masked control alive (-202 on
        // the baseline). A worker thread that exits before the collection takes that residue with it:
        // afterwards the only copy of the address is the slot on the main thread's x86 guest stack.
        private static WeakReference publishedWeak;

        [MethodImpl(MethodImplOptions.NoInlining)]
        private static void PublishOnCurrentThread(bool installRoot)
        {
            object target = new byte[128 * 1024];
            publishedWeak = new WeakReference(target);
            NativePublishObject(target);
            if (installRoot)
                NativeInstallGuestRoot();
        }

        private static void PublishRooted()
        {
            PublishOnCurrentThread(true);
        }

        private static void PublishMasked()
        {
            PublishOnCurrentThread(false);
        }

        [MethodImpl(MethodImplOptions.NoInlining)]
        private static WeakReference PrepareWeakObject(bool installRoot)
        {
            publishedWeak = null;
            // Static methods, not a lambda: a closure object would be reachable from the Thread and
            // could end up holding the target.
            Thread worker = new Thread(installRoot ? new ThreadStart(PublishRooted) : new ThreadStart(PublishMasked));
            worker.Start();
            worker.Join();
            WeakReference weak = publishedWeak;
            publishedWeak = null;
            return weak;
        }

        [MethodImpl(MethodImplOptions.NoInlining)]
        private static void ScrubAndCollect()
        {
            for (int round = 0; round < 4; round++)
            {
                for (int i = 0; i < 64; i++)
                {
                    byte[] scratch = new byte[4096 + i];
                    scratch[0] = (byte)i;
                }
                GC.Collect();
                GC.WaitForPendingFinalizers();
            }
        }

        // P4: an x86 internal call raises a managed exception with mono_raise_exception. The exception
        // must reach this catch block, the x86 guest must keep a consistent state (the embedding host
        // checks its callee-saved registers and stack pointer around mono_runtime_invoke), and reverse
        // internal calls must keep working afterwards. Many rounds so a per-exception guest stack leak
        // or a stale emulator state cannot hide.
        public static int RunExceptionProbe()
        {
            const int rounds = 1000;
            int caught = 0;
            for (int i = 0; i < rounds; i++)
            {
                try
                {
                    NativeThrowFromGuest(i);
                    return -301;
                }
                catch (ArgumentNullException)
                {
                    caught++;
                }
            }

            if (NativeAdd(0x5200, 0x44) != 0x5244)
                return -302;
            return caught == rounds ? 0x5244 : -303;
        }

        [MethodImpl(MethodImplOptions.NoInlining)]
        private static int ManagedBenchIdentity(int value)
        {
            return value;
        }

        // Cost of one ARM64 Mono -> x86 internal call, the transition Unity pays on every engine call
        // (Graphics.DrawMesh, transforms, input...). A plain managed call of the same shape is timed as the
        // baseline so the report can show the transition overhead alone. Timing comes from the x86 host's
        // clock_gettime: this probe's managed directory only has mscorlib, so there is no Stopwatch.
        public static int RunIcallBenchmark()
        {
            const int warmup = 20000;
            const int calls = 1000000;
            int sink = 0;
            for (int i = 0; i < warmup; i++)
            {
                sink += NativeBenchIdentity(i);
                sink += ManagedBenchIdentity(i);
            }

            long t0 = NativeNowNs();
            for (int i = 0; i < calls; i++)
                sink += NativeBenchIdentity(i);
            long t1 = NativeNowNs();
            for (int i = 0; i < calls; i++)
                sink += ManagedBenchIdentity(i);
            long t2 = NativeNowNs();

            NativeReportBench(t1 - t0, t2 - t1, calls);
            GC.KeepAlive(sink);
            return NativeBenchIdentity(0x5244);
        }

        // P5: both runtimes rely on SIGSEGV in the same process. ARM64 Mono turns a fault at a small
        // address inside JIT code into NullReferenceException; Box64 write-protects pages holding x86
        // code it has translated and takes the fault to notice self-modifying code. Mono installs its
        // handlers during JIT init and replaces Box64's, so every Box64 fault must be handed back to
        // Box64 while every managed null dereference must still reach Mono. The rounds interleave the
        // two kinds of fault on the same thread.
        public static int RunSignalProbe()
        {
            const int rounds = 200;
            int nullReferences = 0;
            for (int i = 0; i < rounds; i++)
            {
                if (NativeSmcRound(i) != i * 3 + 7)
                    return -501;
                try
                {
                    ReadThroughNull(null);
                    return -502;
                }
                catch (NullReferenceException)
                {
                    nullReferences++;
                }
            }
            return nullReferences == rounds ? 0x5244 : -503;
        }

        public static int RunGcRootProbe()
        {
            WeakReference rooted = PrepareWeakObject(true);
            ScrubAndCollect();
            bool guestRootWasSeen = rooted.IsAlive;
            NativeClearGuestRoot();

            WeakReference masked = PrepareWeakObject(false);
            ScrubAndCollect();
            bool negativeControlDied = !masked.IsAlive;
            NativeClearGuestRoot();

            if (!guestRootWasSeen)
                return -201;
            if (!negativeControlDied)
                return -202;
            return 0x5244;
        }
    }
}
