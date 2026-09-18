// C++ baseline for the Vela matmul benchmark.
//
// The same 512x512 double matrix multiply is compiled three ways so the
// comparison is honest about *where* any speed difference comes from:
//
//   naive          : what people usually write
//   restrict       : hand-annotated non-aliasing, so MSVC can trust it
//   restrict+omp   : the strongest fair C++ equivalent of what Vela emits
//
// MSVC's OpenMP 2.0 wants the loop variable declared outside the for-init, so
// that is what this file does too.

#include <cstdio>
#include <cstdint>
#include <chrono>

static const int N  = 512;
static const int NN = N * N;

static double A[NN], B[NN], C[NN];

int main()
{
    for (int i = 0; i < NN; ++i) {
        A[i] = (double)(i % 7)  * 0.5  + 1.0;
        B[i] = (double)(i % 11) * 0.25 + 2.0;
    }

#ifdef RESTRICT
    const double* __restrict ar = A;
    const double* __restrict br = B;
    double*       __restrict cr = C;
#else
    const double* ar = A;
    const double* br = B;
    double*       cr = C;
#endif

    int i = 0;

    auto t0 = std::chrono::steady_clock::now();

#ifdef USE_OMP
    #pragma omp parallel for
#endif
    for (i = 0; i < N; ++i) {
        // j and k are declared *inside* the parallel region, so each thread
        // gets its own copy.  Declaring them outside would be a data race —
        // exactly the class of bug Vela's `parallel for` refuses to compile.
        int j, k;
        for (j = 0; j < N; ++j) {
            double s = 0.0;
            for (k = 0; k < N; ++k) {
                s += ar[i * N + k] * br[k * N + j];
            }
            cr[i * N + j] = s;
        }
    }

    auto t1 = std::chrono::steady_clock::now();

    double sum = 0.0;
    for (i = 0; i < NN; ++i) sum += C[i];

    std::printf("checksum %lld\n", (long long)sum);
    std::printf("seconds %.6f\n",
                std::chrono::duration<double>(t1 - t0).count());
    return 0;
}
