package io.github.c4software.gliff.data.video

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import io.github.c4software.gliff.domain.session.Box
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws decoded pictures on a `GLSurfaceView`, in two passes. The first turns
 * a picture's planes into RGB at the stream's size, recombining the Dual420
 * chroma when there is an auxiliary picture, once per frame. The second puts
 * that on screen where [place] says, magnified or not, on every redraw: it
 * keeps each stream pixel sharp and only blends across its edges, so text
 * stays readable when pinched larger instead of smearing.
 *
 * The last picture is kept, to draw again when the view is moved or its GL
 * context comes back.
 */
class VideoRenderer(private val view: GLSurfaceView) : GLSurfaceView.Renderer {
    private val incoming = AtomicReference<YuvPicture?>()
    private var current: YuvPicture? = null

    /** Whether `current` is in `rgb` yet, in this GL context. */
    private var converted = false

    @Volatile
    private var picture: Box? = null

    private var viewWidth = 0
    private var viewHeight = 0
    private var convert = 0
    private var display = 0
    private val planes = Array(6) { Texture() }
    private val rgb = Texture()
    private var framebuffer = 0

    /** A new picture to show. Any thread; one never drawn is recycled. */
    fun show(next: YuvPicture) {
        incoming.getAndSet(next)?.recycle()
        view.requestRender()
    }

    /** Where in the view the picture goes, in pixels; null shows nothing. Any thread. */
    fun place(box: Box?) {
        if (box == picture) return
        picture = box
        view.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: everything made in the old one is gone.
        convert = program(CONVERT)
        display = program(DISPLAY)
        val ids = IntArray(7)
        GLES30.glGenTextures(7, ids, 0)
        planes.forEachIndexed { i, t -> t.reset(ids[i], GLES30.GL_NEAREST) }
        rgb.reset(ids[6], GLES30.GL_LINEAR)
        val fb = IntArray(1)
        GLES30.glGenFramebuffers(1, fb, 0)
        framebuffer = fb[0]
        converted = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        incoming.getAndSet(null)?.let {
            current?.recycle()
            current = it
            converted = false
        }
        val shown = current
        if (shown != null && !converted) {
            convert(shown)
            converted = true
        }
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val box = picture ?: return
        if (shown == null || box.width <= 0 || box.height <= 0) return
        GLES30.glUseProgram(display)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rgb.id)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(display, "rgb"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(display, "size"), rgb.width.toFloat(), rgb.height.toFloat())
        GLES30.glUniform4f(
            GLES30.glGetUniformLocation(display, "rect"),
            box.x.toFloat(), box.y.toFloat(), box.width.toFloat(), box.height.toFloat(),
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(display, "viewHeight"), viewHeight.toFloat())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Upload the planes and turn them into RGB in `rgb`, row 0 the top one. */
    private fun convert(p: YuvPicture) {
        val width = p.main.width
        val height = p.main.height
        val aux = p.aux?.takeIf { it.width == width && it.height == height }
        upload(planes[0], p.main.y, width, height)
        upload(planes[1], p.main.u, width / 2, height / 2)
        upload(planes[2], p.main.v, width / 2, height / 2)
        if (aux != null) {
            upload(planes[3], aux.y, width, height)
            upload(planes[4], aux.u, width / 2, height / 2)
            upload(planes[5], aux.v, width / 2, height / 2)
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        if (rgb.width != width || rgb.height != height) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rgb.id)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            rgb.width = width
            rgb.height = height
            rgb.format = GLES30.GL_RGBA8
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, rgb.id, 0,
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) Log.e(TAG, "framebuffer incomplete: $status")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(convert)
        for (i in 0 until 6) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, planes[i].id)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(convert, PLANES[i]), i)
        }
        GLES30.glUniform1i(GLES30.glGetUniformLocation(convert, "dual"), if (aux != null) 1 else 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(convert, "halfHeight"), height / 2)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewWidth, viewHeight)
    }

    /**
     * Upload a `width x height` plane. One interleaved with the other chroma
     * plane goes up as two channels a texel, so its own samples land in red.
     */
    private fun upload(texture: Texture, plane: YuvPlane, width: Int, height: Int) {
        val pairs = plane.pixelStride == 2
        val internal = if (pairs) GLES30.GL_RG8 else GLES30.GL_R8
        val format = if (pairs) GLES30.GL_RG else GLES30.GL_RED
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture.id)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, plane.rowStride / plane.pixelStride)
        val data = plane.data
        data.position(0)
        if (texture.width != width || texture.height != height || texture.format != internal) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, width, height, 0, format, GLES30.GL_UNSIGNED_BYTE, data)
            texture.width = width
            texture.height = height
            texture.format = internal
        } else {
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height, format, GLES30.GL_UNSIGNED_BYTE, data)
        }
    }

    private class Texture {
        var id = 0
        var width = 0
        var height = 0
        var format = 0

        fun reset(id: Int, filter: Int) {
            this.id = id
            width = 0
            height = 0
            format = 0
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
    }

    private fun program(fragment: String): Int {
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, shader(GLES30.GL_VERTEX_SHADER, VERTEX))
        GLES30.glAttachShader(program, shader(GLES30.GL_FRAGMENT_SHADER, fragment))
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) Log.e(TAG, "program: ${GLES30.glGetProgramInfoLog(program)}")
        return program
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) Log.e(TAG, "shader: ${GLES30.glGetShaderInfoLog(shader)}")
        return shader
    }

    private companion object {
        const val TAG = "gliff"

        val PLANES = arrayOf("mainY", "mainU", "mainV", "auxY", "auxU", "auxV")

        /** A quad over the whole target, from the vertex index alone. */
        const val VERTEX = """#version 300 es
void main() {
    gl_Position = vec4(float((gl_VertexID & 1) * 2 - 1), float((gl_VertexID >> 1) * 2 - 1), 0.0, 1.0);
}
"""

        /**
         * Planes to RGB, one target pixel per stream pixel. In Dual420 the
         * chroma the main picture dropped comes from the auxiliary one, laid
         * out as the server's gliff-proto `chroma.rs` splits it: odd columns
         * of even rows in its chroma planes, odd rows in its luma plane, U in
         * the top half and V in the bottom one. Otherwise each chroma sample
         * covers its 2x2 block. BT.709, limited range, as the server encodes.
         */
        const val CONVERT = """#version 300 es
precision highp float;
uniform sampler2D mainY, mainU, mainV, auxY, auxU, auxV;
uniform int dual;
uniform int halfHeight;
out vec4 color;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 c = p / 2;
    float y = texelFetch(mainY, p, 0).r;
    vec2 uv;
    if (dual == 0 || (p.x % 2 == 0 && p.y % 2 == 0)) {
        uv = vec2(texelFetch(mainU, c, 0).r, texelFetch(mainV, c, 0).r);
    } else if (p.y % 2 == 0) {
        uv = vec2(texelFetch(auxU, c, 0).r, texelFetch(auxV, c, 0).r);
    } else {
        uv = vec2(texelFetch(auxY, ivec2(p.x, c.y), 0).r, texelFetch(auxY, ivec2(p.x, halfHeight + c.y), 0).r);
    }
    float l = y * 255.0 - 16.0;
    float cb = uv.x * 255.0 - 128.0;
    float cr = uv.y * 255.0 - 128.0;
    vec3 rgb = vec3(1.1644 * l + 1.7927 * cr, 1.1644 * l - 0.2132 * cb - 0.5329 * cr, 1.1644 * l + 2.1124 * cb);
    color = vec4(clamp(rgb / 255.0, 0.0, 1.0), 1.0);
}
"""

        /**
         * The RGB picture into `rect` (top-left origin, view pixels), black
         * around it. Magnified, each stream pixel stays a flat square and only
         * the last screen pixel of its edge blends with the next one; at or
         * below its own size that is plain bilinear filtering.
         */
        const val DISPLAY = """#version 300 es
precision highp float;
uniform sampler2D rgb;
uniform vec2 size;
uniform vec4 rect;
uniform float viewHeight;
out vec4 color;
void main() {
    vec2 at = vec2(gl_FragCoord.x, viewHeight - gl_FragCoord.y);
    vec2 t = (at - rect.xy) / rect.zw;
    if (any(lessThan(t, vec2(0.0))) || any(greaterThanEqual(t, vec2(1.0)))) {
        color = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec2 texel = t * size - 0.5;
    vec2 i = floor(texel);
    vec2 sharpness = max(rect.zw / size, vec2(1.0));
    vec2 f = clamp((texel - i - 0.5) * sharpness + 0.5, 0.0, 1.0);
    color = texture(rgb, (i + f + 0.5) / size);
}
"""
    }
}
