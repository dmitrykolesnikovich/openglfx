package com.huskerdev.openglfx.canvas.implementations.async

import com.huskerdev.ojgl.GLContext
import com.huskerdev.openglfx.GLExecutor
import com.huskerdev.openglfx.GLExecutor.Companion.glFinish
import com.huskerdev.openglfx.GLExecutor.Companion.glViewport
import com.huskerdev.openglfx.canvas.GLProfile
import com.huskerdev.openglfx.GL_TEXTURE_2D
import com.huskerdev.openglfx.canvas.GLCanvas
import com.huskerdev.openglfx.internal.GLFXUtils.Companion.D3DTextureResource
import com.huskerdev.openglfx.internal.GLFXUtils.Companion.disposeManually
import com.huskerdev.openglfx.internal.GLInteropType
import com.huskerdev.openglfx.internal.PassthroughShader
import com.huskerdev.openglfx.internal.Size
import com.huskerdev.openglfx.internal.fbo.Framebuffer
import com.huskerdev.openglfx.internal.fbo.MultiSampledFramebuffer
import com.huskerdev.openglfx.internal.d3d9.D3D9Device
import com.huskerdev.openglfx.internal.d3d9.D3D9Texture
import com.huskerdev.openglfx.internal.d3d9.NVDXInterop
import com.huskerdev.openglfx.internal.d3d9.NVDXInterop.Companion.interopDevice
import com.huskerdev.openglfx.internal.d3d9.WGL_ACCESS_WRITE_DISCARD_NV
import com.sun.prism.Graphics
import com.sun.prism.GraphicsPipeline
import com.sun.prism.PixelFormat
import com.sun.prism.Texture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

open class AsyncNVDXInteropCanvasImpl(
    private val executor: GLExecutor,
    profile: GLProfile,
    flipY: Boolean,
    msaa: Int
) : GLCanvas(GLInteropType.NVDXInterop, profile, flipY, msaa, true){

    private val paintLock = Object()
    private val blitLock = Object()

    private var drawSize = Size(-1, -1)
    private var interopTextureSize = Size(-1, -1)
    private var resultSize = Size(-1, -1)

    private lateinit var resultFBO: Framebuffer
    private lateinit var interThreadFBO: Framebuffer
    private lateinit var fbo: Framebuffer
    private lateinit var msaaFBO: MultiSampledFramebuffer

    private lateinit var context: GLContext
    private lateinit var resultContext: GLContext
    private val fxDevice = D3D9Device.fxInstance

    private lateinit var fxD3DTexture: D3D9Texture
    private lateinit var fxTexture: Texture

    private var needsBlit = AtomicBoolean(false)
    private lateinit var interopObject: NVDXInterop.NVDXObject

    private lateinit var passthroughShader: PassthroughShader

    private fun initializeGLThread(){
        resultContext = GLContext.create(0, profile == GLProfile.Core)
        resultContext.makeCurrent()
        passthroughShader = PassthroughShader()
        GLContext.clear()

        thread(isDaemon = true) {
            context = GLContext.create(resultContext.handle, profile == GLProfile.Core)
            context.makeCurrent()
            executor.initGLFunctions()

            while(!disposed){
                paint()
                synchronized(blitLock) {
                    interopTextureSize.changeOnDifference(drawSize){
                        updateInterTextureSize(sizeWidth, sizeHeight)
                    }
                    fbo.blitTo(interThreadFBO.id)
                    glFinish()
                }
                needsBlit.set(true)

                synchronized(paintLock){
                    if(!disposed) paintLock.wait()
                }
            }

            if(::fxTexture.isInitialized) fxTexture.disposeManually(false)
            GLContext.delete(context)
            GLContext.delete(resultContext)
            if(::interopObject.isInitialized) interopObject.dispose()
        }
    }

    private fun paint(){
        drawSize.changeOnDifference(scaledWidth, scaledHeight){
            updateFramebufferSize(sizeWidth, sizeHeight)
            fireReshapeEvent(sizeWidth, sizeHeight)
        }

        glViewport(0, 0, drawSize.sizeWidth, drawSize.sizeHeight)
        fireRenderEvent(if (msaa != 0) msaaFBO.id else fbo.id)
        if (msaa != 0)
            msaaFBO.blitTo(fbo.id)
    }

    private fun updateFramebufferSize(width: Int, height: Int) {
        if (::fbo.isInitialized) {
            fbo.delete()
            if(msaa != 0) msaaFBO.delete()
        }

        // Create GL texture
        fbo = Framebuffer(width, height)
        fbo.bindFramebuffer()

        // Create multi-sampled framebuffer
        if(msaa != 0) {
            msaaFBO = MultiSampledFramebuffer(msaa, width, height)
            msaaFBO.bindFramebuffer()
        }
    }

    private fun updateInterTextureSize(width: Int, height: Int){
        if(::interThreadFBO.isInitialized)
            interThreadFBO.delete()
        interThreadFBO = Framebuffer(width, height)
    }

    override fun onNGRender(g: Graphics) {
        if(scaledWidth == 0 || scaledHeight == 0 || disposed)
            return

        if(!::resultContext.isInitialized)
            initializeGLThread()

        if (needsBlit.getAndSet(false)) {
            resultContext.makeCurrent()

            synchronized(blitLock){
                resultSize.changeOnDifference(interopTextureSize){
                    updateInteropTextureSize(sizeWidth, sizeHeight)
                    glViewport(0, 0, sizeWidth, sizeHeight)
                }

                interopObject.lock()
                passthroughShader.copy(interThreadFBO, resultFBO)
                interopObject.unlock()
            }

            GLContext.clear()
        }
        if(this::fxTexture.isInitialized)
            drawResultTexture(g, fxTexture)
    }

    private fun updateInteropTextureSize(width: Int, height: Int){
        if(this::fxTexture.isInitialized) {
            interopObject.dispose()
            resultFBO.delete()
            fxTexture.disposeManually()
        }

        resultFBO = Framebuffer(width, height)
        resultFBO.bindFramebuffer()

        // Create and register D3D9 shared texture
        fxD3DTexture = fxDevice.createTexture(width, height)
        NVDXInterop.linkShareHandle(fxD3DTexture.handle, fxD3DTexture.sharedHandle)

        // Create default JavaFX texture and replace a native handle with custom one.
        fxTexture = GraphicsPipeline.getDefaultResourceFactory().createTexture(PixelFormat.BYTE_BGRA_PRE, Texture.Usage.DYNAMIC, Texture.WrapMode.CLAMP_TO_EDGE, width, height)
        fxTexture.makePermanent()
        D3D9Device.replaceD3DTextureInResource(fxTexture.D3DTextureResource, fxD3DTexture.handle)

        // Create interop texture
        interopObject = interopDevice.registerObject(fxD3DTexture.handle, resultFBO.texture, GL_TEXTURE_2D, WGL_ACCESS_WRITE_DISCARD_NV)
    }

    override fun repaint() {
        synchronized(paintLock){
            paintLock.notifyAll()
        }
    }

    override fun timerTick() {
        if(needsBlit.get())
            markDirty()
    }

    override fun dispose() {
        super.dispose()
        repaint()
    }
}