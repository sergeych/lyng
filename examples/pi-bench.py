#  Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
#

import time
from multiprocessing import Process

def piSpigot(iThread, nx):
    piIter = 0
    pi = [None] * nx
    boxes = nx * 10 // 3
    reminders = [None]*boxes
    i = 0
    while i < boxes:
        reminders[i] = 2
        i += 1
    heldDigits = 0
    i = 0
    while i < nx:
        carriedOver = 0
        sum = 0
        j = boxes - 1
        while j >= 0:
            reminders[j] *= 10
            sum = reminders[j] + carriedOver
            quotient = sum // (j * 2 + 1)
            reminders[j] = sum % (j * 2 + 1)
            carriedOver = quotient * j
            j -= 1
        reminders[0] = sum % 10
        q = sum // 10
        if q == 9:
            heldDigits += 1
        elif q == 10:
            q = 0
            k = 1
            while k <= heldDigits:
                replaced = pi[i - k]
                if replaced == 9:
                    replaced = 0
                else:
                    replaced += 1
                pi[i - k] = replaced
                k += 1
            heldDigits = 1
        else:
            heldDigits = 1
        pi[piIter] = q
        piIter += 1
        i += 1
    res = ""
    for i in range(len(pi)-8, len(pi), 1):
        res += str(pi[i])
    print(str(iThread) + ": " + res)

def createProcesses():
    THREADS = 1
    WORK_SIZE = 500
    print("piBench (python3): THREADS = " + str(THREADS) + ", WORK_SIZE = " + str(WORK_SIZE))
    pa = []
    for i in range(THREADS):
        p = Process(target=piSpigot, args=(i, WORK_SIZE))
        p.start()
        pa.append(p)
    for p in pa:
        p.join()

if __name__ == "__main__":
    t1 = time.time()
    createProcesses()
    dt = time.time() - t1
    print("total time: %i ms" % (dt*1000))
