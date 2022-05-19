#pragma once

#include "renderer.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

class Page;

class RendererFallback final : public Renderer
{
public:
    RendererFallback(Page&, unsigned width, unsigned height);
    virtual ~RendererFallback();

    virtual void surfaceCreated(ANativeWindow*) override;
    virtual void surfaceChanged(int format, unsigned width, unsigned height) override;
    virtual void surfaceRedrawNeeded() override;
    virtual void surfaceDestroyed() override;

    virtual void handleExportedBuffer(const std::shared_ptr<ExportedBuffer>&) override;

private:
    struct FrameContext;

    void scheduleFrame(FrameContext*);
    void renderFrame(const std::shared_ptr<ExportedBuffer>&, bool dispatchFrameCompleteCallback);

    static int s_updateCallback(int, int, void*);
    static void s_frameCallback(long, void*);

    Page& m_page;

    struct
    {
        unsigned width { 0 };
        unsigned height { 0 };
    } m_size;

    struct
    {
        EGLDisplay display { EGL_NO_DISPLAY };

        EGLConfig config { 0 };
        EGLContext context { EGL_NO_CONTEXT };
        EGLSurface surface { EGL_NO_SURFACE };

        PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getNativeClientBufferANDROID;
        PFNEGLCREATEIMAGEKHRPROC createImageKHR;
        PFNEGLDESTROYIMAGEKHRPROC destroyImageKHR;
        PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imageTargetTexture2DOES;
    } m_egl;

    struct
    {
        GLint vertexShader { 0 };
        GLint fragmentShader { 0 };
        GLint program { 0 };
        GLuint texture { 0 };

        GLint attr_pos;
        GLint attr_texture;
        GLint uniform_texture;
    } m_gl;

    struct
    {
        int fd { -1 };
    } m_update;

    std::shared_ptr<ExportedBuffer> m_lockedBuffer;
};
