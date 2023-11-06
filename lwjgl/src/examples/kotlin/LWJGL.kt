import com.huskerdev.openglfx.GLProfile
import com.huskerdev.openglfx.OpenGLCanvas
import com.huskerdev.openglfx.OpenGLCanvasAnimator
import com.huskerdev.openglfx.lwjgl.LWJGLExecutor.Companion.LWJGL_MODULE
import com.sun.prism.GraphicsPipeline
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import scene.ExampleScene


fun main() {
    System.setProperty("prism.vsync", "false")
    Application.launch(ExampleApp::class.java)
}

class ExampleApp: Application(){

    override fun start(stage: Stage?) {
        stage!!.title = "OpenGLCanvas example"
        stage.width = 800.0
        stage.height = 600.0
        
        val glCanvas = createGLCanvas()

        stage.scene = Scene(StackPane(createDebugPanel(glCanvas), glCanvas))
        stage.show()
    }

    private fun createGLCanvas(): OpenGLCanvas {
        val canvas = OpenGLCanvas.create(LWJGL_MODULE, msaa = 4, profile = GLProfile.Core, multiThread = true)
        canvas.animator = OpenGLCanvasAnimator(60.0)

        val renderExample = ExampleScene()
        canvas.addOnInitEvent(renderExample::init)
        canvas.addOnReshapeEvent(renderExample::reshape)
        canvas.addOnRenderEvent(renderExample::render)

        return canvas
    }

    private fun createDebugPanel(canvas: OpenGLCanvas) = VBox().apply{
        children.add(Label("OpenGLCanvas is not opaque, so you can see this text"))
        children.add(Label("----------------------------------------"))
        arrayListOf(
            "PIPELINE" to GraphicsPipeline.getPipeline().javaClass.canonicalName.split(".")[3],
            "METHOD" to canvas::class.java.simpleName,
            "MSAA" to canvas.msaa,
            "PROFILE" to canvas.profile,
            "FLIP_Y" to canvas.flipY,
            "MULTI_THREAD" to canvas.multiThread,
            "FPS" to "-",
            "SIZE" to "0x0"
        ).forEach {
            children.add(BorderPane().apply {
                maxWidth = 190.0
                left = Label(it.first + ":")
                right = Label(it.second.toString()).apply { id = it.first }
            })
        }
        canvas.addOnRenderEvent { e ->
            Platform.runLater {
                (scene.lookup("#FPS") as Label).text = "${e.fps}/${(1000 / (e.delta * 1000)).toInt()}"
                (scene.lookup("#SIZE") as Label).text = "${e.width}x${e.height}"
            }
        }
    }
}