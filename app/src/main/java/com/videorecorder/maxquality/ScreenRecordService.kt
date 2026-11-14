private fun removeOverlayButton() {
        overlayWindow?.removeView(overlayView!!)
        overlayView = null
        overlayWindow = null
    }