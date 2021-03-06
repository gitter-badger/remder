package ph.samson.remder.app

import java.awt.Desktop
import java.io.ByteArrayOutputStream
import java.util

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import better.files.File
import javax.xml.bind.DatatypeConverter
import net.sourceforge.plantuml.SourceStringReader
import org.commonmark.node.{FencedCodeBlock, Node}
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.{
  CoreHtmlNodeRenderer,
  HtmlNodeRendererContext,
  HtmlRenderer
}
import ph.samson.remder.app.Presenter.Present

import scala.io.Source

class Renderer(presenter: ActorRef) extends Actor with ActorLogging {
  import Renderer._

  private val parser = Parser.builder().build()
  private val renderer = HtmlRenderer
    .builder()
    .nodeRendererFactory(
      (context: HtmlNodeRendererContext) => new PlantUmlRenderer(context)
    )
    .build()

  private def styled(title: String, htmlBody: String) = {
    s"""|<html>
        |  <head>
        |    <title>$title</title>
        |    <style>$DefaultCss</style>
        |  </head>
        |  <body>
        |    $htmlBody
        |  </body>
        |</html>
        |""".stripMargin
  }

  override def receive: Receive = {
    case ToViewer(markdown) =>
      presenter ! Present(
        styled(markdown.nameWithoutExtension,
               renderer.render(markdown.fileReader(parser.parseReader))))
    case ToBrowser(markdown) =>
      val content = markdown.contentAsString
      val hash = content.hashCode
      val target = OutDir / s"remder-$hash.html"
      if (target.notExists) {
        target.writeText(
          styled(markdown.nameWithoutExtension,
                 renderer.render(parser.parse(content))))
      }
      Desktop.getDesktop.browse(target.uri)
  }
}

object Renderer {
  val OutDir: File =
    sys.env.get("REMDER_OUTDIR").map(File(_)).getOrElse(File.temp)

  val DefaultCss: String = Source.fromResource("default.css").mkString

  case class ToViewer(markdown: File)
  case class ToBrowser(markdown: File)

  def props(presenter: ActorRef): Props = Props(new Renderer(presenter))

  class PlantUmlRenderer(context: HtmlNodeRendererContext)
      extends NodeRenderer {
    import PlantUmlRenderer._

    private val writer = context.getWriter
    private val default = new CoreHtmlNodeRenderer(context)

    override def getNodeTypes: util.Set[Class[_ <: Node]] =
      util.Collections.singleton(classOf[FencedCodeBlock])

    override def render(node: Node): Unit = node match {
      case fcb: FencedCodeBlock if NodeTypes.contains(fcb.getInfo) =>
        val nodeType = fcb.getInfo
        val source = fcb.getLiteral
        val hash = source.hashCode
        val target = OutDir / s"$hash.png"
        val targetDesc = OutDir / s"$hash.desc"
        val (description, bytes) = if (target.isReadable) {
          targetDesc.contentAsString -> target.byteArray
        } else {
          val os = new ByteArrayOutputStream()
          val desc =
            new SourceStringReader(s"@start$nodeType\n$source\n@end$nodeType")
              .outputImage(os)
              .getDescription
          val output = os.toByteArray
          target.writeByteArray(output)
          targetDesc.writeText(desc)
          desc -> output
        }

        val rendered = DatatypeConverter.printBase64Binary(bytes)
        val dataUri = s"data:image/png;base64,$rendered"
        val attrs = new util.HashMap[String, String]()
        attrs.put("src", dataUri)
        attrs.put("title", description)

        writer.line()
        writer.tag("img", attrs, true)
        writer.line()
      case other => default.render(other)
    }
  }

  object PlantUmlRenderer {
    val NodeTypes = Set("uml", "salt", "ditaa", "dot", "jcckit")
  }
}
