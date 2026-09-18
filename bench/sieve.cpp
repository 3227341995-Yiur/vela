// C++ baseline for the Vela sieve benchmark.
//
// std::vector<uint8_t>::operator[] performs no bounds check, so this is the
// unchecked C++ that Vela's checked arithmetic is being measured against.
// Both sides run the identical algorithm.

#include <cstdio>
#include <cstdint>
#include <vector>
#include <chrono>

int main()
{
    const long long n = 20000000LL;
    std::vector<uint8_t> comp((size_t)n, 0);

    auto t0 = std::chrono::steady_clock::now();

    for (long long i = 2; i * i < n; ++i) {
        if (comp[(size_t)i] == 0) {
            for (long long j = i * i; j < n; j += i) {
                comp[(size_t)j] = 1;
            }
        }
    }

    auto t1 = std::chrono::steady_clock::now();

    long long count = 0;
    for (long long k = 2; k < n; ++k) {
        if (comp[(size_t)k] == 0) ++count;
    }

    std::printf("primes %lld\n", count);
    std::printf("seconds %.6f\n",
                std::chrono::duration<double>(t1 - t0).count());
    return 0;
}
