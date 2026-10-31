package me.rerere.rikkahub.pipeline

import android.os.Bundle
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.container.TerminalTranscriptWidthIndex
import me.rerere.rikkahub.ui.pages.container.TerminalRowWidthMeasurer
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Width oracle only; Compose test idling is NEVER used in the paired performance driver. */
class TerminalAlternateWidthFontTest {
    @get:Rule val compose=createAndroidComposeRule<TerminalPipelineTestActivity>()
    @Test fun alternateCacheEqualsRealFontFullLayout() {
        lateinit var resolver: FontFamily.Resolver
        compose.setContent{resolver=LocalFontFamilyResolver.current};compose.waitForIdle()
        var checks=0
        compose.runOnIdle {
            for(density in listOf(Density(1f),Density(1.33f,1.3f),Density(2.75f,1.15f)))for(direction in LayoutDirection.entries) {
                val t=TerminalEmulator(80,6,100).apply{feed("\u001b[?1049h\u001b[?25lplain ffi\r\n中文日本語\r\ne\u0301 A\u030a\r\n👩‍💻 ⚙️\r\nעברית العربية\r\nend\r")}
                val style=resolveDefaults(TextStyle(fontFamily=JetbrainsMono,fontSize=14.sp),direction)
                val full=TextMeasurer(resolver,density,direction,cacheSize=0)
                val index=TerminalTranscriptWidthIndex()
                // Reflective candidate-only contract keeps the shared APK loadable with the old target.
                val method=index.javaClass.getMethod("fullVirtualWidth",TerminalEmulator.RenderFrame::class.java,Any::class.java,kotlin.jvm.functions.Function1::class.java)
                for(command in listOf("","\u001b[2;1H\u001b[2Ktiny\r","\u001b[3;1H\u001b[1;3m漢e\u0301\u001b[0m\r","\u001b[4;1H\u001b[L","\u001b[2;1H\u001b[M","\u001b[?25h\u001b[2;8H","\u001b[?25l")) {
                    if(command.isNotEmpty())t.feed(command)
                    val frame=t.renderFrame();val row=TerminalRowWidthMeasurer(frame,style,density,resolver)
                    val calculate:(androidx.compose.ui.text.AnnotatedString)->Int=row::width
                    val actual=method.invoke(index,frame,style to density,calculate) as Int
                    val expected=frame.rows.maxOf{full.measure(it.text,style,softWrap=false,maxLines=1,skipCache=true).size.width}
                    assertEquals("$density/$direction/$checks",expected,actual);checks++
                }
            }
        }
        assertEquals(42,checks)
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{putString("stream","\nALT_WIDTH_FONT_CHECKS $checks\n")})
    }
}
