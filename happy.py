count = 0
for n1 in range(10):
    for n2 in range(10):
        for n3 in range(10):
            for n4 in range(10):
                for n5 in range(10):
                    for n6 in range(10):
                        if n1 + n2 + n3 == n4 + n5 + n6:
                            count += 1

print(count)
