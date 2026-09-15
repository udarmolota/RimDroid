using System;
using System.Threading;

namespace RimDroid.MonoArm64Probe
{
    public static class EntryPoint
    {
        public static int RunBasic()
        {
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
