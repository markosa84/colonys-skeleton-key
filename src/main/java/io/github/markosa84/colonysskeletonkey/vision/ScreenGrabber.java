package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * The one thing {@link GameScreen} asks the outside world for: the pixels inside a screen box.
 *
 * <p>It exists because {@code java.awt.Robot} cannot be constructed - nor subclassed - in a headless
 * JVM, and the tests are headless by design (they analyse PNGs and never touch a screen). Behind
 * this interface a fake can serve a labelled frame instead, which is exactly what the live grabber
 * hands over anyway, so everything above it - the box arithmetic, the canvas compositing, the
 * lockpick-counter hash, {@code LivePoller} and {@code LiveLockView} - is testable without a display.
 */
interface ScreenGrabber {

    /** The pixels inside {@code box}, in screen coordinates. */
    BufferedImage grab(Rectangle box);
}
