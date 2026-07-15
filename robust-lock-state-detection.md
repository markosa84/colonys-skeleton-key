# Robust Lock-State Detection from Game Screenshots

Your **30° counter-clockwise rotation is a good first step**, but I would not hard-code exactly 30°. The camera perspective may vary slightly. Estimate the angle from the long plate edges or the rows of holes.

In this screenshot, there are visibly **5 plates**.

## Recommended processing pipeline

| Stage | Transformation/method | Purpose |
|---|---|---|
| 1 | Locate and crop the lock | Remove the black area, UI text and background |
| 2 | Estimate rotation | Make the hole rows approximately horizontal |
| 3 | Perspective correction | Make hole spacing and size more consistent |
| 4 | Convert to Lab or grayscale | Separate brightness from colour |
| 5 | Percentile normalization + CLAHE | Reduce gamma, brightness and HDR differences |
| 6 | Morphological black-hat | Highlight dark holes on bright metal |
| 7 | Adaptive threshold | Produce a binary hole mask |
| 8 | Fit a structured 5 × 7 model | Reject scratches, shadows and background objects |
| 9 | Compare hole centres with their surroundings | Determine which position contains the pin |

## 1. Locate the lock first

Do not apply normalization to the full screenshot because the large black area will distort the brightness statistics.

Either:

- crop using coordinates relative to image width and height, if the lock is always in approximately the same place;
- use template matching on the front face of the lock;
- use ORB feature matching if scale and position can change considerably.

Template matching should preferably use an **edge or gradient image**, rather than the original colours.

## 2. Rotation and perspective correction

Rotation alone makes the rows horizontal, but it does not remove perspective. The holes farther from the camera remain smaller and closer together.

A better approach is:

1. Detect long plate edges using `Canny` and `HoughLinesP`.
2. Calculate the median angle of the approximately parallel lines.
3. Rotate using that angle.
4. Apply `warpPerspective()` using four stable points around the plate area.

However, the five plates are at slightly different depths. Therefore, one global perspective transform may not perfectly correct all five rows.

A particularly robust solution is to:

- detect each row separately;
- fit a line through the row;
- extract a narrow strip around that line;
- transform each strip into a horizontal rectangle.

Then every plate becomes an independent one-dimensional problem.

## 3. Brightness and gamma normalization

Use the luminance channel rather than RGB values:

```java
Mat lab = new Mat();
Imgproc.cvtColor(roi, lab, Imgproc.COLOR_BGR2Lab);

List<Mat> channels = new ArrayList<>();
Core.split(lab, channels);
Mat luminance = channels.get(0);
```

Within the lock ROI:

1. Clip approximately the darkest 2% and brightest 2% of pixels.
2. Stretch the remaining range.
3. Apply CLAHE.

```java
CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
Mat normalized = new Mat();
clahe.apply(luminance, normalized);
```

CLAHE is useful here because it works locally. A plate in shadow can be normalized differently from a plate under a bright reflection.

Avoid relying on a fixed threshold such as `brightness < 60`. That will break when gamma or HDR tone mapping changes.

## 4. Highlight the holes with black-hat morphology

The holes are locally dark areas surrounded by bright metal. A morphological **black-hat** transformation is well suited to this:

```java
Mat kernel = Imgproc.getStructuringElement(
    Imgproc.MORPH_ELLIPSE,
    new Size(kernelWidth, kernelHeight)
);

Mat blackHat = new Mat();
Imgproc.morphologyEx(
    normalized,
    blackHat,
    Imgproc.MORPH_BLACKHAT,
    kernel
);
```

The kernel should be slightly larger than one hole. Do not use a fixed pixel size across resolutions. Estimate the approximate hole diameter and define the kernel relative to it.

For example:

```text
kernel width  ≈ 1.5–2.0 × hole width
kernel height ≈ 1.5–2.0 × hole height
```

The result should suppress much of the plate surface while making the empty holes bright in the transformed image.

## 5. Use adaptive thresholding

```java
Mat binary = new Mat();

Imgproc.adaptiveThreshold(
    blackHat,
    binary,
    255,
    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
    Imgproc.THRESH_BINARY,
    31,
    -2
);
```

Follow this with a small morphological opening to remove isolated noise:

```java
Mat smallKernel = Imgproc.getStructuringElement(
    Imgproc.MORPH_ELLIPSE,
    new Size(3, 3)
);

Imgproc.morphologyEx(
    binary,
    binary,
    Imgproc.MORPH_OPEN,
    smallKernel
);
```

## 6. Do not detect arbitrary circles—fit the known structure

A simple Hough-circle detector will probably produce false detections from:

- scratches;
- rivets;
- reflections;
- the difficulty icons;
- plate edges.

Instead, use the known puzzle geometry:

- hole centres lie on parallel lines;
- each valid row contains seven equally spaced positions;
- neighbouring rows have similar spacing;
- there are relatively few plates.

After finding candidate hole centres, cluster them by their transformed `y` coordinate. For every cluster, fit:

\[
x_j = x_0 + j \cdot d,\qquad j=0,\ldots,6
\]

where:

- \(x_0\) is the first slot position;
- \(d\) is the average spacing;
- \(j\) is the hole index.

RANSAC or a small exhaustive search can tolerate missing holes and false candidates.

Count a plate only when a row has a good seven-position fit. That is more reliable than counting the visible plate outlines.

## 7. Reduce each plate to a one-dimensional signal

After rectifying one plate into a horizontal strip, average the black-hat response vertically:

\[
p(x)=\frac{1}{h}\sum_y \text{blackHat}(x,y)
\]

Empty holes should create strong peaks in this profile.

Because one hole contains a pin, that position may produce:

- a weaker dark-hole peak;
- a brighter centre;
- a lower proportion of dark pixels.

Fit seven equally spaced slot positions to the profile. Then inspect the seven positions individually.

This is simpler and more robust than trying to segment the complete physical shape of every plate.

## 8. Detect the pin using local contrast

For every predicted slot, compare:

- a small central disk;
- an annulus around the centre.

Useful measurements include:

```text
darkPixelRatio = dark pixels in centre / centre area
centreBrightness - surroundingBrightness
centreMedian / surroundingMedian
```

An empty hole normally has:

- a dark centre;
- a brighter metallic ring;
- a high dark-pixel ratio.

A pin-filled position normally has:

- a brighter or coloured centre;
- a lower dark-pixel ratio;
- weaker black-hat response.

Select the slot with the strongest “filled” score.

With zero-based indexing:

```java
int middleSlot = 3;
int offset = detectedPinSlot - middleSlot;
```

This gives offsets from `-3` to `+3`. Reverse the subtraction if the game uses the opposite left/right convention.

## Most robust final pipeline

```text
Screenshot
    ↓
Locate lock ROI
    ↓
Estimate angle dynamically
    ↓
Rotate and perspective-correct
    ↓
Extract each plate as a horizontal strip
    ↓
Lab luminance channel
    ↓
Percentile normalization
    ↓
CLAHE
    ↓
Black-hat morphology
    ↓
Vertical averaging → 1D profile
    ↓
Fit seven equally spaced slots
    ↓
Classify the filled slot
    ↓
offset = slotIndex - 3
```

Also calculate a confidence value using:

- error of the seven-slot spacing;
- number of detected hole candidates;
- difference between the best and second-best pin scores;
- similarity of hole size across the row.

When confidence is low, return “state not detected” rather than guessing.

---

**Language note:** “a lock in **a role-playing game**” rather than “an role playing game”; “where the holes are **in relation to** the pin” sounds more natural than “in regards to the pin.”
