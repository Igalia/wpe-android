#include "renderer_fallback.h"

#include "logging.h"
#include "looperthread.h"
#include "browser.h"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/choreographer.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/looper.h>
#include <stdint.h>
#include <sys/eventfd.h>
#include <vector>
#include <wpe-android/view-backend-exportable.h>

struct RendererFallback::FrameContext {
    RendererFallback& renderer;
    std::shared_ptr<ExportedBuffer> buffer;
    bool dispatchFrameCompleteCallback;
};

RendererFallback::RendererFallback(Page& page, unsigned width, unsigned height)
    : m_page(page)
    , m_size({ width, height })
{
    m_egl.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(m_egl.display, nullptr, nullptr);

    ALOGV("RendererFallback: clnt exts %s", eglQueryString(EGL_NO_DISPLAY, EGL_EXTENSIONS));
    ALOGV("RendererFallback: disp exts %s", eglQueryString(m_egl.display, EGL_EXTENSIONS));

    m_egl.getNativeClientBufferANDROID = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
            eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    m_egl.createImageKHR = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
            eglGetProcAddress("eglCreateImageKHR"));
    m_egl.destroyImageKHR = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
            eglGetProcAddress("eglDestroyImageKHR"));
    m_egl.imageTargetTexture2DOES = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
            eglGetProcAddress("glEGLImageTargetTexture2DOES"));

    m_update.fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    ALOGV("RendererFallback: %p m_update.fd %d", this, m_update.fd);
}

RendererFallback::~RendererFallback()
{
    if (m_update.fd != -1)
        close(m_update.fd);
}

static const char* s_vertexShaderSource =
        "attribute vec2 pos;\n"
        "attribute vec2 texture;\n"
        "varying vec2 v_texture;\n"
        "void main() {\n"
        "  v_texture = texture;\n"
        "  gl_Position = vec4(pos, 0, 1);\n"
        "}\n";
static const char* s_fragmentShaderSource =
        "precision mediump float;\n"
        "uniform sampler2D u_texture;\n"
        "varying vec2 v_texture;\n"
        "void main() {\n"
        "  gl_FragColor = texture2D(u_texture, v_texture);\n"
        "}\n";

void RendererFallback::surfaceCreated(ANativeWindow* window)
{
    if (m_egl.context != EGL_NO_CONTEXT) {
        surfaceDestroyed();
    }

    ALOGV("RendererFallback::surfaceCreated() window %p size (%u,%u)", window, m_size.width, m_size.height);

    static const EGLint configAttributes[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 1,
            EGL_GREEN_SIZE, 1,
            EGL_BLUE_SIZE, 1,
            EGL_ALPHA_SIZE, 1,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_NONE,
    };

    EGLint count;
    if (!eglChooseConfig(m_egl.display, configAttributes, nullptr, 0, &count))
        return;

    EGLint numConfigs = 0;
    std::vector<EGLConfig> configs(count);
    if (!eglChooseConfig(m_egl.display, configAttributes, configs.data(), count, &numConfigs))
        return;
    if (!numConfigs)
        return;
    m_egl.config = configs[0];

    static const EGLint contextAttributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE,
    };

    m_egl.context = eglCreateContext(m_egl.display, m_egl.config, EGL_NO_CONTEXT, contextAttributes);
    ALOGV("RendererFallback: context %p err %x", m_egl.context, eglGetError());
    m_egl.surface = eglCreateWindowSurface(m_egl.display, m_egl.config, window, nullptr);
    ALOGV("RendererFallback: surface %p err %x", m_egl.surface, eglGetError());

    eglBindAPI(EGL_OPENGL_ES_API);
    eglMakeCurrent(m_egl.display, m_egl.surface, m_egl.surface, m_egl.context);

    ALOGV("RendererFallback: initializing");

    m_gl.vertexShader = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(m_gl.vertexShader, 1, &s_vertexShaderSource, nullptr);
    glCompileShader(m_gl.vertexShader);

    m_gl.fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(m_gl.fragmentShader, 1, &s_fragmentShaderSource, nullptr);
    glCompileShader(m_gl.fragmentShader);

    m_gl.program = glCreateProgram();
    glAttachShader(m_gl.program, m_gl.vertexShader);
    glAttachShader(m_gl.program, m_gl.fragmentShader);
    glLinkProgram(m_gl.program);

    m_gl.attr_pos = glGetAttribLocation(m_gl.program, "pos");
    m_gl.attr_texture = glGetAttribLocation(m_gl.program, "texture");
    m_gl.uniform_texture = glGetUniformLocation(m_gl.program, "u_texture");

    glGenTextures(1, &m_gl.texture);
    glBindTexture(GL_TEXTURE_2D, m_gl.texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glBindTexture(GL_TEXTURE_2D, 0);

    ALOGV("RendererFallback: shaders %d/%d program %d texture %u",
          m_gl.vertexShader, m_gl.fragmentShader, m_gl.program, m_gl.texture);
}

void RendererFallback::surfaceChanged(int format, unsigned width, unsigned height)
{
    m_size.width = width;
    m_size.height = height;
}

void RendererFallback::surfaceRedrawNeeded()
{
    if (!m_lockedBuffer)
        return;
    if (m_lockedBuffer->size.width != m_size.width || m_lockedBuffer->size.height != m_size.height)
        return;

    auto exportedBuffer = std::exchange(m_lockedBuffer, nullptr);
    scheduleFrame(new FrameContext { *this, exportedBuffer, false });
}

void RendererFallback::surfaceDestroyed()
{
    ALOGV("RendererFallback::surfaceDestroyed()");
    if (m_egl.context == EGL_NO_CONTEXT) {
        return;
    }

    eglMakeCurrent(m_egl.display, m_egl.surface, m_egl.surface, m_egl.context);

    if (m_egl.context != EGL_NO_CONTEXT) {
        if (eglDestroyContext(m_egl.display,  m_egl.context) == EGL_FALSE) {
            ALOGE("eglDestroyContext() failed: %d", eglGetError());
        }
        m_egl.context = EGL_NO_CONTEXT;
    }
    if (m_egl.surface != EGL_NO_SURFACE) {
        if (eglDestroySurface(m_egl.display, m_egl.surface) == EGL_FALSE) {
            ALOGE("eglDestroySurface() failed: %d", eglGetError());
        }
        m_egl.surface = EGL_NO_SURFACE;
    }
}

void RendererFallback::handleExportedBuffer(const std::shared_ptr<ExportedBuffer>& exportedBuffer)
{
    ALOGV("RendererFallback::handleExportedBuffer() buffer %p", exportedBuffer.get());
    scheduleFrame(new FrameContext { *this, exportedBuffer, true });
}

void RendererFallback::scheduleFrame(FrameContext* frameContext)
{
    auto* looper = LooperThread::instance().looper();
    ALooper_addFd(looper, m_update.fd, ALOOPER_POLL_CALLBACK, ALOOPER_EVENT_INPUT,
                  s_updateCallback, frameContext);

    uint64_t inc = 1;
    write(m_update.fd, &inc, sizeof(inc));

    ALooper_wake(looper);
}

void RendererFallback::renderFrame(const std::shared_ptr<ExportedBuffer>& buffer, bool dispatchFrameCompleteCallback)
{
    ALOGV("RendererFallback::renderFrame() buffer %p", buffer.get());

    {
        if (m_lockedBuffer) {
            wpe_android_view_backend_exportable_dispatch_release_buffer(m_page.exportable(),
                    m_lockedBuffer->buffer,
                    m_lockedBuffer->poolID,
                    m_lockedBuffer->bufferID);

            m_lockedBuffer = nullptr;
        }
        m_lockedBuffer = buffer;

        if (dispatchFrameCompleteCallback)
            wpe_android_view_backend_exportable_dispatch_frame_complete(m_page.exportable());
    }

    eglMakeCurrent(m_egl.display, m_egl.surface, m_egl.surface, m_egl.context);
    {
        glViewport(0, 0, m_size.width, m_size.height);
        glClearColor(0, 1, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT);

        EGLClientBuffer clientBuffer = m_egl.getNativeClientBufferANDROID(buffer->buffer);
        EGLImageKHR image = m_egl.createImageKHR(m_egl.display, EGL_NO_CONTEXT,
                                                 EGL_NATIVE_BUFFER_ANDROID, clientBuffer, nullptr);
        ALOGV("RendererFallback: clientBuffer %p => image %p, err %x, size (%u,%u)",
                clientBuffer, image, eglGetError(), m_size.width, m_size.height);

        glUseProgram(m_gl.program);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, m_gl.texture);
        m_egl.imageTargetTexture2DOES(GL_TEXTURE_2D, image);
        glUniform1i(m_gl.uniform_texture, 0);

        static float positionCoords[] = {
                -1,1,  1,1,  -1,-1,  1,-1,
        };
        static float textureCoords[] = {
                0,0,  1,0,  0,1,  1,1,
        };

        glVertexAttribPointer(m_gl.attr_pos, 2, GL_FLOAT, GL_FALSE, 0, positionCoords);
        glVertexAttribPointer(m_gl.attr_texture, 2, GL_FLOAT, GL_FALSE, 0, textureCoords);

        glEnableVertexAttribArray(m_gl.attr_pos);
        glEnableVertexAttribArray(m_gl.attr_texture);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        glDisableVertexAttribArray(m_gl.attr_pos);
        glDisableVertexAttribArray(m_gl.attr_texture);

        m_egl.destroyImageKHR(m_egl.display, image);

        eglSwapBuffers(m_egl.display, m_egl.surface);
    }
}

int RendererFallback::s_updateCallback(int fd, int events, void* data)
{
    {
        uint64_t dec;
        read(fd, &dec, sizeof(dec));
    }

    ALOGV("RendererFallback::s_updateCallback() fd %d events %d data %p", fd, events, data);
    ALOGV("RendererFallback::s_updateCallback() AChoreographer %p", AChoreographer_getInstance());
    AChoreographer_postFrameCallback(AChoreographer_getInstance(), s_frameCallback, data);
    return 1;
}

void RendererFallback::s_frameCallback(long, void* data)
{
    ALOGV("RendererFallback::s_frameCallback() data %p", data);
    auto* context = static_cast<FrameContext*>(data);
    ALOGV("RendererFallback::s_frameCallback() renderer %p buffer %p", &context->renderer, context->buffer.get());

    Browser::getInstance().invoke(
            [](void* data) {
                auto* context = static_cast<FrameContext*>(data);
                context->renderer.renderFrame(context->buffer, context->dispatchFrameCompleteCallback);
            }, context,
            [](void* data) {
                delete static_cast<FrameContext*>(data);
            });
}