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
    }
}
