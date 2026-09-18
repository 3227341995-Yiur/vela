// C++ baseline for the Vela Mandelbrot benchmark.
//
//   naive        : straightforward, no OpenMP
//   restrict+omp : hand-parallelised with the loop bodies' locals private,
//                  which is the strongest fair C++ equivalent

#include <cstdio>
#include <cstdint>
#include <vector>
#include <chrono>

static const int W = 1600;
static const int H = 1200;

int main()
{
    std::vector<int> img((size_t)W * (size_t)H, 0);

    auto t0 = std::chrono::steady_clock::now();

    int y = 0;
#ifdef USE_OMP
    #pragma omp parallel for
#endif
    for (y = 0; y < H; ++y) {
        int x;
        for (x = 0; x < W; ++x) {
            double cr = (double)x / 1600.0 * 3.5 - 2.5;
            double ci = (double)y / 1200.0 * 2.0 - 1.0;
            double zr = 0.0, zi = 0.0;
            int it = 0;
            while (it < 100 && zr * zr + zi * zi < 4.0) {
                double t = zr * zr - zi * zi + cr;
                zi = 2.0 * zr * zi + ci;
                zr = t;
                ++it;
            }
            img[(size_t)y * W + (size_t)x] = it;
        }
    }

    auto t1 = std::chrono::steady_clock::now();

    long long sum = 0;
    for (size_t i = 0; i < img.size(); ++i) sum += img[i];

    std::printf("checksum %lld\n", sum);
    std::printf("seconds %.6f\n",
                std::chrono::duration<double>(t1 - t0).count());
    return 0;
}
