package studio.acmenra.control.feature.events.presentation.components

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(imgId, previewUrl) => {
    var img = document.getElementById(imgId);
    if (!img) {
        img = document.createElement('img');
        img.id = imgId;
        img.referrerPolicy = 'no-referrer';
        img.style.position = 'fixed';
        img.style.pointerEvents = 'none';
        img.style.objectFit = 'cover';
        img.style.borderRadius = '16px';
        img.style.zIndex = '10';
        document.body.appendChild(img);
    }
    if (img.getAttribute('data-src') !== previewUrl) {
        img.setAttribute('data-src', previewUrl);
        img.src = previewUrl;
    }
}""",
)
private external fun jsEnsurePreviewImg(
    imgId: String,
    previewUrl: String,
)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(imgId, x, y, width, height, clipLeft, clipTop, clipRight, clipBottom, isHidden) => {
    var img = document.getElementById(imgId);
    if (img) {
        var winW = window.innerWidth || (document.documentElement ? document.documentElement.clientWidth : 0) || 0;
        var winH = window.innerHeight || (document.documentElement ? document.documentElement.clientHeight : 0) || 0;
        var effClipLeft = Math.max(0, clipLeft);
        var effClipTop = Math.max(0, clipTop);
        var effClipRight = Math.min(winW, clipRight);
        var effClipBottom = Math.min(winH, clipBottom);

        var visLeft = Math.max(x, effClipLeft);
        var visTop = Math.max(y, effClipTop);
        var visRight = Math.min(x + width, effClipRight);
        var visBottom = Math.min(y + height, effClipBottom);

        if (isHidden || visRight <= visLeft || visBottom <= visTop || width <= 0 || height <= 0) {
            img.style.display = 'none';
        } else {
            img.style.display = 'block';
            img.style.left = x + 'px';
            img.style.top = y + 'px';
            img.style.width = width + 'px';
            img.style.height = height + 'px';

            var insetTop = Math.max(0, effClipTop - y);
            var insetRight = Math.max(0, (x + width) - effClipRight);
            var insetBottom = Math.max(0, (y + height) - effClipBottom);
            var insetLeft = Math.max(0, effClipLeft - x);
            if (insetTop > 0 || insetRight > 0 || insetBottom > 0 || insetLeft > 0) {
                img.style.clipPath = 'inset(' + insetTop + 'px ' + insetRight + 'px ' + insetBottom + 'px ' + insetLeft + 'px round 16px)';
            } else {
                img.style.clipPath = '';
            }
        }
    }
}""",
)
private external fun jsUpdatePreviewGeometry(
    imgId: String,
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    clipLeft: Double,
    clipTop: Double,
    clipRight: Double,
    clipBottom: Double,
    isHidden: Boolean,
)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(imgId) => {
    var img = document.getElementById(imgId);
    if (img && img.parentNode) {
        img.parentNode.removeChild(img);
    }
}""",
)
private external fun jsRemovePreviewImg(imgId: String)

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun(
    """(videoUrl) => {
    var id = 'control-events-video-test-' + Date.now();
    var video = document.createElement('video');
    video.id = id;
    video.src = videoUrl;
    video.setAttribute('referrerpolicy', 'no-referrer');
    video.referrerPolicy = 'no-referrer';
    video.controls = true;
    video.autoplay = true;
    video.style.position = 'fixed';
    video.style.top = '50%';
    video.style.left = '50%';
    video.style.transform = 'translate(-50%, -50%)';
    video.style.maxWidth = '90vw';
    video.style.maxHeight = '80vh';
    video.style.zIndex = '99999';
    video.style.borderRadius = '16px';
    video.style.boxShadow = '0 8px 32px rgba(0,0,0,0.5)';
    video.style.backgroundColor = '#000';

    var backdrop = document.createElement('div');
    backdrop.id = id + '-backdrop';
    backdrop.style.position = 'fixed';
    backdrop.style.top = '0';
    backdrop.style.left = '0';
    backdrop.style.width = '100vw';
    backdrop.style.height = '100vh';
    backdrop.style.backgroundColor = 'rgba(0,0,0,0.6)';
    backdrop.style.zIndex = '99998';

    var keyHandler = function(e) {
        if (e.key === 'Escape' || e.keyCode === 27) {
            e.stopPropagation();
            e.preventDefault();
            cleanup();
        }
    };
    window.addEventListener('keydown', keyHandler);

    function cleanup() {
        try { video.pause(); } catch(e) {}
        window.removeEventListener('keydown', keyHandler);
        if (video.parentNode) video.parentNode.removeChild(video);
        if (backdrop.parentNode) backdrop.parentNode.removeChild(backdrop);
    }

    backdrop.onclick = cleanup;
    video._cleanup = cleanup;

    document.body.appendChild(backdrop);
    document.body.appendChild(video);
    return id;
}""",
)
private external fun jsCreateAndPlayVideo(videoUrl: String): String

class EventMediaPlayerBrowserTest {
    @Test
    fun testRealBrowserPreviewCreationAndReferrerPolicy() {
        val testId = "test-preview-real-1"
        val testUrl = "https://cdn.control.studio/preview/1.jpg"

        jsEnsurePreviewImg(testId, testUrl)

        val element = document.getElementById(testId) as? HTMLImageElement
        assertNotNull(element, "Preview element must exist in real browser DOM")
        assertEquals("no-referrer", element.referrerPolicy)
        assertEquals("10", element.style.zIndex, "Preview z-index must be 10 (below modal popups)")
        assertEquals(testUrl, element.src)

        // Cleaning up
        jsRemovePreviewImg(testId)
        assertNull(document.getElementById(testId), "Preview element must be removed from real browser DOM")
    }

    @Test
    fun testRealBrowserClippingWithClipPath() {
        val testId = "test-preview-clip-1"
        val testUrl = "https://cdn.control.studio/preview/clip.jpg"

        jsEnsurePreviewImg(testId, testUrl)
        val element = document.getElementById(testId) as HTMLImageElement

        // Scenario A: Inside viewport and clipping bounds
        jsUpdatePreviewGeometry(
            imgId = testId,
            x = 100.0,
            y = 100.0,
            width = 200.0,
            height = 150.0,
            clipLeft = 50.0,
            clipTop = 50.0,
            clipRight = 400.0,
            clipBottom = 400.0,
            isHidden = false,
        )
        assertEquals("block", element.style.display)
        assertEquals("100px", element.style.left)
        assertEquals("100px", element.style.top)
        assertEquals("", element.style.getPropertyValue("clip-path"))

        // Scenario B: Partially scrolled outside parent top boundary (y=30, clipTop=50)
        jsUpdatePreviewGeometry(
            imgId = testId,
            x = 100.0,
            y = 30.0,
            width = 200.0,
            height = 150.0,
            clipLeft = 50.0,
            clipTop = 50.0,
            clipRight = 400.0,
            clipBottom = 400.0,
            isHidden = false,
        )
        assertEquals("block", element.style.display)
        assertTrue(
            element.style.getPropertyValue("clip-path").contains("inset(20px"),
            "Top 20px must be clipped via CSS clip-path inset when overflowing top boundary",
        )

        // Scenario C: Completely scrolled out above container (y=-200)
        jsUpdatePreviewGeometry(
            imgId = testId,
            x = 100.0,
            y = -200.0,
            width = 200.0,
            height = 150.0,
            clipLeft = 50.0,
            clipTop = 50.0,
            clipRight = 400.0,
            clipBottom = 400.0,
            isHidden = false,
        )
        assertEquals("none", element.style.display, "Element must be display: none when completely scrolled out")

        // Cleanup
        jsRemovePreviewImg(testId)
    }

    @Test
    fun testRealBrowserVideoOverlayAndEscapeKeyDismissal() {
        val videoUrl = "https://cdn.control.studio/video/1.mp4"
        val videoId = jsCreateAndPlayVideo(videoUrl)

        val videoElement = document.getElementById(videoId) as? HTMLVideoElement
        val backdropElement = document.getElementById("$videoId-backdrop") as? HTMLDivElement

        assertNotNull(videoElement, "Video element must be added to real browser DOM")
        assertNotNull(backdropElement, "Backdrop element must be added to real browser DOM")
        assertEquals("no-referrer", videoElement.getAttribute("referrerpolicy"))
        assertEquals("99999", videoElement.style.zIndex)

        // Dispatch real DOM Escape keydown event to test dismissal and listener cleanup
        val eventInit = KeyboardEventInit(key = "Escape", code = "Escape", bubbles = true, cancelable = true)
        val escEvent = KeyboardEvent("keydown", eventInit)
        window.dispatchEvent(escEvent)

        // Verify elements were removed
        assertNull(
            document.getElementById(videoId),
            "Video element must be removed on Escape key in real browser",
        )
        assertNull(
            document.getElementById("$videoId-backdrop"),
            "Backdrop must be removed on Escape key in real browser",
        )
    }
}
