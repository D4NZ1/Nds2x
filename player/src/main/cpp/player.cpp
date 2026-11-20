// Frontend libretro minimo para Android (JNI): carrega um nucleo melonDS,
// desenha o video numa Surface, toca audio via AAudio e le entradas do Java.
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <aaudio/AAudio.h>
#include <dlfcn.h>
#include <time.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

// Log no logcat e tambem em <pasta do app>/files/log.txt (legivel sem adb).
static FILE* g_logf = nullptr;
static std::mutex g_logMutex;
static std::string g_logBuf;  // copia em memoria, lida pelo menu "Ver log"
static void flog(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
static void flog(const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, "NdsPlayer", "%s", buf);
    std::lock_guard<std::mutex> lk(g_logMutex);
    g_logBuf += buf;
    g_logBuf += '\n';
    if (g_logBuf.size() > 40000) g_logBuf.erase(0, g_logBuf.size() - 30000);
    if (g_logf) {
        fputs(buf, g_logf);
        fputc('\n', g_logf);
        fflush(g_logf);
    }
}
#define LOGI(...) flog(__VA_ARGS__)
#define LOGE(...) flog("ERRO: " __VA_ARGS__)

// ---------------------------------------------------------------- libretro ABI
namespace {

struct retro_system_info {
    const char* library_name;
    const char* library_version;
    const char* valid_extensions;
    bool need_fullpath;
    bool block_extract;
};
struct retro_game_geometry {
    unsigned base_width, base_height, max_width, max_height;
    float aspect_ratio;
};
struct retro_system_timing {
    double fps;
    double sample_rate;
};
struct retro_system_av_info {
    retro_game_geometry geometry;
    retro_system_timing timing;
};
struct retro_game_info {
    const char* path;
    const void* data;
    size_t size;
    const char* meta;
};
struct retro_variable {
    const char* key;
    const char* value;
};

typedef void (*retro_log_printf_t)(int level, const char* fmt, ...);
struct retro_log_callback {
    retro_log_printf_t log;
};

typedef bool (*retro_environment_t)(unsigned cmd, void* data);
typedef void (*retro_video_refresh_t)(const void* data, unsigned width, unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t* data, size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);

enum {
    ENV_GET_CAN_DUPE = 3,
    ENV_SET_MESSAGE = 6,
    ENV_SHUTDOWN = 7,
    ENV_GET_SYSTEM_DIRECTORY = 9,
    ENV_SET_PIXEL_FORMAT = 10,
    ENV_SET_VARIABLES = 16,
    ENV_GET_VARIABLE = 15,
    ENV_GET_VARIABLE_UPDATE = 17,
    ENV_GET_LIBRETRO_PATH = 19,
    ENV_GET_LOG_INTERFACE = 27,
    ENV_GET_SAVE_DIRECTORY = 31,
    ENV_SET_SYSTEM_AV_INFO = 32,
    ENV_SET_GEOMETRY = 37,
    ENV_GET_LANGUAGE = 39,
    ENV_GET_INPUT_BITMASKS = 51,
    ENV_GET_CORE_OPTIONS_VERSION = 52,
    ENV_GET_JIT_CAPABLE = 74,
};

enum { PIXEL_0RGB1555 = 0, PIXEL_XRGB8888 = 1, PIXEL_RGB565 = 2 };

const unsigned DEVICE_JOYPAD = 1;
const unsigned DEVICE_POINTER = 6;
const unsigned ID_JOYPAD_MASK = 256;
const unsigned ID_POINTER_X = 0;
const unsigned ID_POINTER_Y = 1;
const unsigned ID_POINTER_PRESSED = 2;
const unsigned MEMORY_SAVE_RAM = 0;

struct Core {
    void* handle = nullptr;
    void (*set_environment)(retro_environment_t) = nullptr;
    void (*set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*set_input_poll)(retro_input_poll_t) = nullptr;
    void (*set_input_state)(retro_input_state_t) = nullptr;
    void (*init)() = nullptr;
    void (*deinit)() = nullptr;
    unsigned (*api_version)() = nullptr;
    void (*get_system_info)(retro_system_info*) = nullptr;
    void (*get_system_av_info)(retro_system_av_info*) = nullptr;
    bool (*load_game)(const retro_game_info*) = nullptr;
    void (*unload_game)() = nullptr;
    void (*run)() = nullptr;
    void (*reset)() = nullptr;
    size_t (*serialize_size)() = nullptr;
    bool (*serialize)(void*, size_t) = nullptr;
    bool (*unserialize)(const void*, size_t) = nullptr;
    void* (*get_memory_data)(unsigned) = nullptr;
    size_t (*get_memory_size)(unsigned) = nullptr;
};

// ------------------------------------------------------------------ estado global
Core g_core;
bool g_loaded = false;
std::mutex g_coreMutex;  // protege chamadas ao nucleo (retro_run x serializacao)

std::string g_systemDir, g_saveDir, g_romPath, g_corePath;
std::map<std::string, std::string> g_options;
int g_pixelFormat = PIXEL_XRGB8888;

std::atomic<int> g_inputMask{0};
std::atomic<int> g_touchX{0}, g_touchY{0};
std::atomic<bool> g_touchDown{false};
std::atomic<bool> g_fastForward{false};

retro_system_av_info g_av{};
std::atomic<int> g_frameW{256}, g_frameH{384};

// janela / video
std::mutex g_winMutex;
ANativeWindow* g_window = nullptr;
int g_winW = 0, g_winH = 0;

// thread de emulacao
std::thread g_thread;
std::atomic<bool> g_running{false};
std::mutex g_pauseMutex;
std::condition_variable g_pauseCv;
bool g_paused = true;

// SRAM
std::vector<uint8_t> g_sramShadow;

// ------------------------------------------------------------------ audio
const int kOutRate = 48000;
const size_t kRingFrames = 16384;  // potencia de 2
int16_t g_ring[kRingFrames * 2];
std::atomic<uint64_t> g_ringWrite{0}, g_ringRead{0};
AAudioStream* g_stream = nullptr;
double g_resPos = 0.0;
int16_t g_lastL = 0, g_lastR = 0;

aaudio_data_callback_result_t audioCallback(AAudioStream*, void*, void* audioData, int32_t numFrames) {
    int16_t* out = static_cast<int16_t*>(audioData);
    uint64_t r = g_ringRead.load(std::memory_order_relaxed);
    uint64_t w = g_ringWrite.load(std::memory_order_acquire);
    uint64_t avail = w - r;
    int32_t n = (int32_t)std::min<uint64_t>(avail, (uint64_t)numFrames);
    for (int32_t i = 0; i < n; i++) {
        size_t idx = (size_t)((r + i) & (kRingFrames - 1)) * 2;
        out[i * 2] = g_ring[idx];
        out[i * 2 + 1] = g_ring[idx + 1];
    }
    for (int32_t i = n; i < numFrames; i++) {
        out[i * 2] = 0;
        out[i * 2 + 1] = 0;
    }
    g_ringRead.store(r + n, std::memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void audioStart() {
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(b, kOutRate);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setDataCallback(b, audioCallback, nullptr);
    aaudio_result_t res = AAudioStreamBuilder_openStream(b, &g_stream);
    AAudioStreamBuilder_delete(b);
    if (res != AAUDIO_OK) {
        LOGE("AAudio openStream falhou: %s", AAudio_convertResultToText(res));
        g_stream = nullptr;
        return;
    }
    g_ringRead = 0;
    g_ringWrite = 0;
    AAudioStream_requestStart(g_stream);
}

void audioStop() {
    if (g_stream) {
        AAudioStream_requestStop(g_stream);
        AAudioStream_close(g_stream);
        g_stream = nullptr;
    }
}

size_t audioBatch(const int16_t* data, size_t frames) {
    if (!g_stream || g_fastForward.load() || frames == 0) return frames;
    const double inRate = g_av.timing.sample_rate > 1000 ? g_av.timing.sample_rate : 32768.0;
    const double step = inRate / (double)kOutRate;
    uint64_t w = g_ringWrite.load(std::memory_order_relaxed);
    const uint64_t r = g_ringRead.load(std::memory_order_acquire);
    // Interpolacao linear. A posicao p fica entre x[i-1] e x[i]; x[-1] e a ultima
    // amostra do lote anterior.
    double p = g_resPos;
    const double total = (double)frames;
    while (p < total) {
        if (w - r >= kRingFrames - 1) {  // buffer cheio: descarta o resto do lote
            p = total;
            break;
        }
        const size_t i = (size_t)p;
        const double frac = p - (double)i;
        const int16_t l0 = (i == 0) ? g_lastL : data[(i - 1) * 2];
        const int16_t r0 = (i == 0) ? g_lastR : data[(i - 1) * 2 + 1];
        const int16_t l1 = data[i * 2];
        const int16_t r1 = data[i * 2 + 1];
        const size_t idx = (size_t)(w & (kRingFrames - 1)) * 2;
        g_ring[idx] = (int16_t)(l0 + (l1 - l0) * frac);
        g_ring[idx + 1] = (int16_t)(r0 + (r1 - r0) * frac);
        w++;
        p += step;
    }
    g_resPos = p - total;
    if (g_resPos < 0) g_resPos = 0;
    g_lastL = data[(frames - 1) * 2];
    g_lastR = data[(frames - 1) * 2 + 1];
    g_ringWrite.store(w, std::memory_order_release);
    return frames;
}

void audioSample(int16_t l, int16_t r) {
    int16_t s[2] = {l, r};
    audioBatch(s, 1);
}

// ------------------------------------------------------------------ video
// Amostra 16x16 pixels do quadro bruto do nucleo e registra no log (diagnostico de tela branca).
void logFrameStats(unsigned long long n, const void* data, unsigned w, unsigned h, size_t pitch) {
    std::set<uint32_t> colors;
    uint32_t first = 0, top = 0, bottom = 0;
    for (unsigned gy = 0; gy < 16; gy++) {
        for (unsigned gx = 0; gx < 16; gx++) {
            unsigned x = w > 1 ? gx * (w - 1) / 15 : 0;
            unsigned y = h > 1 ? gy * (h - 1) / 15 : 0;
            const uint8_t* row = static_cast<const uint8_t*>(data) + (size_t)y * pitch;
            uint32_t p = (g_pixelFormat == PIXEL_XRGB8888)
                             ? reinterpret_cast<const uint32_t*>(row)[x]
                             : (uint32_t)reinterpret_cast<const uint16_t*>(row)[x];
            colors.insert(p);
            if (gx == 0 && gy == 0) first = p;
            if (gx == 8 && gy == 4) top = p;
            if (gx == 8 && gy == 11) bottom = p;
        }
    }
    flog("quadro %llu: %ux%u pitch=%zu formato=%d cores_distintas(de 256)=%zu primeiro=%08x topo=%08x base=%08x janela=%s",
         n, w, h, pitch, g_pixelFormat, colors.size(), first, top, bottom, g_window ? "ok" : "AUSENTE");
}

void videoRefresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data || width == 0 || height == 0) return;  // frame duplicado
    static unsigned long long frameNo = 0;
    frameNo++;
    if (data == reinterpret_cast<const void*>(-1)) {  // RETRO_HW_FRAME_BUFFER_VALID: render por GPU, nao suportado aqui
        if (frameNo == 1) flog("ERRO: o nucleo entregou quadro de HW render (nao suportado por este frontend)");
        return;
    }
    g_frameW = (int)width;
    g_frameH = (int)height;
    std::lock_guard<std::mutex> lk(g_winMutex);
    if (frameNo == 1 || frameNo == 120 || frameNo == 600 || frameNo == 1800) {
        logFrameStats(frameNo, data, width, height, pitch);
    }
    if (!g_window) return;
    if (g_winW != (int)width || g_winH != (int)height) {
        ANativeWindow_setBuffersGeometry(g_window, (int)width, (int)height, WINDOW_FORMAT_RGBX_8888);
        g_winW = (int)width;
        g_winH = (int)height;
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(g_window, &buf, nullptr) != 0) return;
    uint32_t* dst = static_cast<uint32_t*>(buf.bits);
    const int rows = std::min<int>((int)height, buf.height);
    const int cols = std::min<int>((int)width, buf.width);
    for (int y = 0; y < rows; y++) {
        uint32_t* d = dst + (size_t)y * buf.stride;
        const uint8_t* srcRow = static_cast<const uint8_t*>(data) + (size_t)y * pitch;
        if (g_pixelFormat == PIXEL_XRGB8888) {
            const uint32_t* s = reinterpret_cast<const uint32_t*>(srcRow);
            for (int x = 0; x < cols; x++) {
                uint32_t p = s[x];
                d[x] = (p & 0x0000FF00u) | ((p & 0xFFu) << 16) | ((p >> 16) & 0xFFu) | 0xFF000000u;
            }
        } else if (g_pixelFormat == PIXEL_RGB565) {
            const uint16_t* s = reinterpret_cast<const uint16_t*>(srcRow);
            for (int x = 0; x < cols; x++) {
                uint32_t p = s[x];
                uint32_t r = ((p >> 11) & 31) * 255 / 31;
                uint32_t g = ((p >> 5) & 63) * 255 / 63;
                uint32_t b = (p & 31) * 255 / 31;
                d[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        } else {
            const uint16_t* s = reinterpret_cast<const uint16_t*>(srcRow);
            for (int x = 0; x < cols; x++) {
                uint32_t p = s[x];
                uint32_t r = ((p >> 10) & 31) * 255 / 31;
                uint32_t g = ((p >> 5) & 31) * 255 / 31;
                uint32_t b = (p & 31) * 255 / 31;
                d[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        }
    }
    ANativeWindow_unlockAndPost(g_window);
}

// ------------------------------------------------------------------ input
void inputPoll() {}

int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0) return 0;
    if (device == DEVICE_JOYPAD) {
        int m = g_inputMask.load();
        if (id == ID_JOYPAD_MASK) return (int16_t)m;
        if (id < 16) return (m >> id) & 1;
        return 0;
    }
    if (device == DEVICE_POINTER && index == 0) {
        if (id == ID_POINTER_X) return (int16_t)g_touchX.load();
        if (id == ID_POINTER_Y) return (int16_t)g_touchY.load();
        if (id == ID_POINTER_PRESSED) return g_touchDown.load() ? 1 : 0;
    }
    return 0;
}

// ------------------------------------------------------------------ ambiente
void coreLog(int level, const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    int prio = ANDROID_LOG_INFO;
    if (level == 0) prio = ANDROID_LOG_DEBUG;
    else if (level == 2) prio = ANDROID_LOG_WARN;
    else if (level == 3) prio = ANDROID_LOG_ERROR;
    __android_log_print(prio, "NdsCore", "%s", buf);
    flog("[nucleo] %s", buf);
}

bool environment(unsigned cmd, void* data) {
    switch (cmd) {
        case ENV_GET_CAN_DUPE:
            *static_cast<bool*>(data) = true;
            return true;
        case ENV_SET_MESSAGE:
            return true;
        case ENV_SHUTDOWN:
            return true;
        case ENV_GET_SYSTEM_DIRECTORY:
            *static_cast<const char**>(data) = g_systemDir.c_str();
            return true;
        case ENV_GET_SAVE_DIRECTORY:
            *static_cast<const char**>(data) = g_saveDir.c_str();
            return true;
        case ENV_GET_LIBRETRO_PATH:
            *static_cast<const char**>(data) = g_corePath.c_str();
            return true;
        case ENV_SET_PIXEL_FORMAT: {
            int fmt = *static_cast<int*>(data);
            if (fmt == PIXEL_0RGB1555 || fmt == PIXEL_XRGB8888 || fmt == PIXEL_RGB565) {
                g_pixelFormat = fmt;
                return true;
            }
            return false;
        }
        case ENV_SET_VARIABLES:
            return true;  // as opcoes ficam nos padroes do nucleo
        case ENV_GET_VARIABLE: {
            retro_variable* v = static_cast<retro_variable*>(data);
            auto it = g_options.find(v->key ? v->key : "");
            static std::set<std::string> asked;
            if (asked.insert(v->key ? v->key : "").second) {
                flog("opcao pedida pelo nucleo: %s (%s)", v->key ? v->key : "?",
                     it != g_options.end() ? it->second.c_str() : "padrao do nucleo");
            }
            if (it != g_options.end()) {
                v->value = it->second.c_str();
                return true;
            }
            v->value = nullptr;
            return false;
        }
        case ENV_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = false;
            return true;
        case ENV_GET_LOG_INTERFACE:
            static_cast<retro_log_callback*>(data)->log = coreLog;
            return true;
        case ENV_SET_GEOMETRY: {
            const retro_game_geometry* g = static_cast<const retro_game_geometry*>(data);
            g_av.geometry = *g;
            return true;
        }
        case ENV_SET_SYSTEM_AV_INFO:
            g_av = *static_cast<const retro_system_av_info*>(data);
            return true;
        case ENV_GET_LANGUAGE:
            *static_cast<unsigned*>(data) = 0;  // ingles; o nucleo usa o idioma do firmware
            return true;
        case ENV_GET_INPUT_BITMASKS:
            return true;
        case ENV_GET_CORE_OPTIONS_VERSION:
            *static_cast<unsigned*>(data) = 0;
            return true;
        case ENV_GET_JIT_CAPABLE:
            *static_cast<bool*>(data) = true;
            return true;
        default: {
            static std::set<unsigned> seen;
            if (seen.insert(cmd).second) flog("comando de ambiente nao tratado: %u", cmd);
            return false;
        }
    }
}

// ------------------------------------------------------------------ utilitarios
void loadOptionsFile(const std::string& path) {
    std::ifstream f(path);
    if (!f) return;
    std::string line;
    while (std::getline(f, line)) {
        if (line.empty() || line[0] == '#') continue;
        size_t eq = line.find('=');
        if (eq == std::string::npos) continue;
        auto trim = [](std::string s) {
            size_t a = s.find_first_not_of(" \t\r");
            size_t b = s.find_last_not_of(" \t\r");
            return a == std::string::npos ? std::string() : s.substr(a, b - a + 1);
        };
        g_options[trim(line.substr(0, eq))] = trim(line.substr(eq + 1));
    }
}

bool readFile(const std::string& path, std::vector<uint8_t>& out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    out.assign(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
    return true;
}

bool writeFile(const std::string& path, const void* data, size_t size) {
    std::string tmp = path + ".tmp";
    {
        std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
        if (!f) return false;
        f.write(static_cast<const char*>(data), (std::streamsize)size);
        if (!f) return false;
    }
    return std::rename(tmp.c_str(), path.c_str()) == 0;
}

std::string sramPath() { return g_saveDir + "/game.srm"; }

// chamar com g_coreMutex travado
void flushSram() {
    if (!g_loaded || !g_core.get_memory_data || !g_core.get_memory_size) return;
    void* d = g_core.get_memory_data(MEMORY_SAVE_RAM);
    size_t n = g_core.get_memory_size(MEMORY_SAVE_RAM);
    if (!d || n == 0) return;
    if (g_sramShadow.size() == n && std::memcmp(g_sramShadow.data(), d, n) == 0) return;
    if (writeFile(sramPath(), d, n)) {
        g_sramShadow.assign(static_cast<uint8_t*>(d), static_cast<uint8_t*>(d) + n);
    }
}

void restoreSram() {
    if (!g_core.get_memory_data || !g_core.get_memory_size) return;
    void* d = g_core.get_memory_data(MEMORY_SAVE_RAM);
    size_t n = g_core.get_memory_size(MEMORY_SAVE_RAM);
    if (!d || n == 0) return;
    std::vector<uint8_t> buf;
    if (readFile(sramPath(), buf) && !buf.empty()) {
        std::memcpy(d, buf.data(), std::min(buf.size(), n));
    }
    g_sramShadow.assign(static_cast<uint8_t*>(d), static_cast<uint8_t*>(d) + n);
}

template <typename T>
bool sym(T& out, const char* name) {
    out = reinterpret_cast<T>(dlsym(g_core.handle, name));
    if (!out) LOGE("simbolo ausente no nucleo: %s", name);
    return out != nullptr;
}

bool loadCoreSymbols() {
    bool ok = true;
    ok &= sym(g_core.set_environment, "retro_set_environment");
    ok &= sym(g_core.set_video_refresh, "retro_set_video_refresh");
    ok &= sym(g_core.set_audio_sample, "retro_set_audio_sample");
    ok &= sym(g_core.set_audio_sample_batch, "retro_set_audio_sample_batch");
    ok &= sym(g_core.set_input_poll, "retro_set_input_poll");
    ok &= sym(g_core.set_input_state, "retro_set_input_state");
    ok &= sym(g_core.init, "retro_init");
    ok &= sym(g_core.deinit, "retro_deinit");
    ok &= sym(g_core.api_version, "retro_api_version");
    ok &= sym(g_core.get_system_info, "retro_get_system_info");
    ok &= sym(g_core.get_system_av_info, "retro_get_system_av_info");
    ok &= sym(g_core.load_game, "retro_load_game");
    ok &= sym(g_core.unload_game, "retro_unload_game");
    ok &= sym(g_core.run, "retro_run");
    ok &= sym(g_core.reset, "retro_reset");
    ok &= sym(g_core.serialize_size, "retro_serialize_size");
    ok &= sym(g_core.serialize, "retro_serialize");
    ok &= sym(g_core.unserialize, "retro_unserialize");
    ok &= sym(g_core.get_memory_data, "retro_get_memory_data");
    ok &= sym(g_core.get_memory_size, "retro_get_memory_size");
    return ok;
}

// ------------------------------------------------------------------ loop de emulacao
void emuLoop() {
    using clock = std::chrono::steady_clock;
    auto next = clock::now();
    auto lastFlush = clock::now();
    while (g_running.load()) {
        {
            std::unique_lock<std::mutex> lk(g_pauseMutex);
            if (g_paused) {
                g_pauseCv.wait(lk, [] { return !g_paused || !g_running.load(); });
                next = clock::now();
                continue;
            }
        }
        {
            std::lock_guard<std::mutex> lk(g_coreMutex);
            if (!g_loaded) break;
            g_core.run();
        }
        auto now = clock::now();
        if (now - lastFlush > std::chrono::seconds(10)) {
            std::lock_guard<std::mutex> lk(g_coreMutex);
            flushSram();
            lastFlush = now;
        }
        if (g_fastForward.load()) {
            next = clock::now();
            continue;
        }
        double fps = g_av.timing.fps > 1.0 ? g_av.timing.fps : 60.0;
        double period = 1.0 / fps;
        // ajuste fino pelo nivel do buffer de audio, para nao desviar do relogio do som
        if (g_stream) {
            double fill = (double)(g_ringWrite.load() - g_ringRead.load());
            double target = kOutRate * period * 2.5;
            double err = (fill - target) / (kOutRate * 0.1);
            period *= 1.0 + std::max(-0.01, std::min(0.01, err * 0.01));
        }
        next += std::chrono::duration_cast<clock::duration>(std::chrono::duration<double>(period));
        auto after = clock::now();
        if (next > after) {
            std::this_thread::sleep_until(next);
        } else if (after - next > std::chrono::milliseconds(100)) {
            next = after;  // ficou muito atrasado: reinicia o relogio
        }
    }
}

void stopThread() {
    g_running = false;
    {
        std::lock_guard<std::mutex> lk(g_pauseMutex);
        g_paused = false;
    }
    g_pauseCv.notify_all();
    if (g_thread.joinable()) g_thread.join();
}

}  // namespace

// ------------------------------------------------------------------ JNI
extern "C" {

JNIEXPORT jboolean JNICALL Java_com_ndspack_player_Native_init(JNIEnv* env, jclass, jstring jCorePath,
                                                               jstring jRom, jstring jSysDir,
                                                               jstring jSaveDir, jstring jOptions) {
    auto str = [&](jstring s) {
        const char* c = env->GetStringUTFChars(s, nullptr);
        std::string r(c ? c : "");
        if (c) env->ReleaseStringUTFChars(s, c);
        return r;
    };
    std::lock_guard<std::mutex> lk(g_coreMutex);
    if (g_loaded) return JNI_TRUE;
    g_corePath = str(jCorePath);
    g_romPath = str(jRom);
    g_systemDir = str(jSysDir);
    g_saveDir = str(jSaveDir);
    if (!g_logf) g_logf = fopen((g_systemDir + "/../log.txt").c_str(), "w");
    if (!g_logf) flog("aviso: nao consegui abrir log.txt (errno=%d)", errno);
    flog("player iniciado. nucleo=%s", g_corePath.c_str());
    flog("rom=%s opcoes=%s", g_romPath.c_str(), str(jOptions).c_str());
    loadOptionsFile(str(jOptions));

    g_core.handle = dlopen(g_corePath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!g_core.handle) {
        LOGE("dlopen falhou: %s", dlerror());
        return JNI_FALSE;
    }
    if (!loadCoreSymbols()) return JNI_FALSE;
    if (g_core.api_version() != 1) {
        LOGE("versao de API libretro inesperada: %u", g_core.api_version());
        return JNI_FALSE;
    }

    g_core.set_environment(environment);
    g_core.set_video_refresh(videoRefresh);
    g_core.set_audio_sample(audioSample);
    g_core.set_audio_sample_batch(audioBatch);
    g_core.set_input_poll(inputPoll);
    g_core.set_input_state(inputState);
    g_core.init();

    retro_system_info info{};
    g_core.get_system_info(&info);
    LOGI("nucleo: %s %s need_fullpath=%d ext=%s", info.library_name ? info.library_name : "?",
         info.library_version ? info.library_version : "?", info.need_fullpath ? 1 : 0,
         info.valid_extensions ? info.valid_extensions : "?");

    retro_game_info game{};
    game.path = g_romPath.c_str();
    std::vector<uint8_t> romData;
    if (!info.need_fullpath) {
        if (!readFile(g_romPath, romData)) {
            LOGE("nao consegui ler a ROM");
            g_core.deinit();
            return JNI_FALSE;
        }
        game.data = romData.data();
        game.size = romData.size();
        if (romData.size() > 0x20) {
            flog("rom: %zu bytes, titulo=%.12s codigo=%.4s", romData.size(),
                 reinterpret_cast<const char*>(romData.data()),
                 reinterpret_cast<const char*>(romData.data()) + 0x0C);
        }
    }
    if (!g_core.load_game(&game)) {
        LOGE("retro_load_game falhou");
        g_core.deinit();
        return JNI_FALSE;
    }
    g_loaded = true;
    g_core.get_system_av_info(&g_av);
    g_frameW = (int)g_av.geometry.base_width;
    g_frameH = (int)g_av.geometry.base_height;
    restoreSram();
    LOGI("jogo carregado: %ux%u @ %.3f fps, audio %.0f Hz", g_av.geometry.base_width,
         g_av.geometry.base_height, g_av.timing.fps, g_av.timing.sample_rate);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL Java_com_ndspack_player_Native_getLog(JNIEnv* env, jclass) {
    std::string s;
    {
        std::lock_guard<std::mutex> lk(g_logMutex);
        s = g_logBuf;
    }
    for (char& c : s) {
        unsigned char u = (unsigned char)c;
        if (u >= 0x80 || (u < 0x20 && c != '\n')) c = '?';
    }
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jintArray JNICALL Java_com_ndspack_player_Native_getVideoSize(JNIEnv* env, jclass) {
    jint v[2] = {g_frameW.load(), g_frameH.load()};
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, v);
    return arr;
}

JNIEXPORT void JNICALL Java_com_ndspack_player_Native_setSurface(JNIEnv* env, jclass, jobject surface) {
    std::lock_guard<std::mutex> lk(g_winMutex);
    if (g_window) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }
    g_winW = g_winH = 0;
    if (surface) g_window = ANativeWindow_fromSurface(env, surface);
}

JNIEXPORT void JNICALL Java_com_ndspack_player_Native_start(JNIEnv*, jclass) {
    if (!g_loaded || g_running.load()) return;
    audioStart();
    g_running = true;
    {
        std::lock_guard<std::mutex> lk(g_pauseMutex);
        g_paused = false;
    }
    g_thread = std::thread(emuLoop);
}

JNIEXPORT void JNICALL Java_com_ndspack_player_Native_setPaused(JNIEnv*, jclass, jboolean paused) {
    {
        std::lock_guard<std::mutex> lk(g_pauseMutex);
        g_paused = paused == JNI_TRUE;
    }
    g_pauseCv.notify_all();
    if (paused) {
        if (g_stream) AAudioStream_requestPause(g_stream);
        std::lock_guard<std::mutex> lk(g_coreMutex);
        flushSram();
    } else {
        if (g_stream) AAudioStream_requestStart(g_stream);
    }
}

JNIEXPORT void JNICALL Java_com_ndspack_player_Native_stop(JNIEnv*, jclass) {
    stopThread();
    audioStop();
    std::lock_guard<std::mutex> lk(g_coreMutex);
    if (g_loaded) {
        flushSram();
        g_core.unload_game();
        g_core.deinit();
        g_loaded = false;
    }
    if (g_core.handle) {
        dlclose(g_core.handle);
        g_core = Core();
    }
}

JNIEXPORT void JNICALL Java_com_ndspack_player_Native_setInput(JNIEnv*, jclass, jint mask) {
    g_inputMask = mask;
}

JNIEXPORT void JNICALL Java_com_ndspack_player_Native_setTouch(JNIEnv*, jclass, jint x, jint y, jboolean down) {
    g_touchX = x;
    g_touchY = y;
    g_touchDown = down == JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_ndspack_player_Native_setFastForward(JNIEnv*, jclass, jboolean ff) {
    g_fastForward = ff == JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_ndspack_player_Native_reset(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_coreMutex);
    if (g_loaded) g_core.reset();
}

JNIEXPORT jboolean JNICALL Java_com_ndspack_player_Native_saveState(JNIEnv* env, jclass, jstring jPath) {
    const char* c = env->GetStringUTFChars(jPath, nullptr);
    std::string path(c ? c : "");
    if (c) env->ReleaseStringUTFChars(jPath, c);
    std::lock_guard<std::mutex> lk(g_coreMutex);
    if (!g_loaded) return JNI_FALSE;
    size_t n = g_core.serialize_size();
    if (n == 0) return JNI_FALSE;
    std::vector<uint8_t> buf(n);
    if (!g_core.serialize(buf.data(), n)) return JNI_FALSE;
    return writeFile(path, buf.data(), n) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_ndspack_player_Native_loadState(JNIEnv* env, jclass, jstring jPath) {
    const char* c = env->GetStringUTFChars(jPath, nullptr);
    std::string path(c ? c : "");
    if (c) env->ReleaseStringUTFChars(jPath, c);
    std::vector<uint8_t> buf;
    if (!readFile(path, buf) || buf.empty()) return JNI_FALSE;
    std::lock_guard<std::mutex> lk(g_coreMutex);
    if (!g_loaded) return JNI_FALSE;
    return g_core.unserialize(buf.data(), buf.size()) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
