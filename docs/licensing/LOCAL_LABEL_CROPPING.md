# Local label cropping

CocktAIls uses OpenCV Android 4.10.0 for an optional, on-device suggestion of a rectangular bottle-label crop. OpenCV is distributed under the Apache License 2.0; see the upstream [license](https://github.com/opencv/opencv/blob/4.10.0/LICENSE). This dependency does not change the app's code license or the licensing of recipe/catalogue data.

On the bottle-label capture screen, the app analyzes a downsampled luminance frame on-device and draws a conservative rectangular edge suggestion after it remains stable across several frames. The user still takes the photo explicitly. The captured photo then goes through the existing review/crop step; a live outline is only a guide and is not silently treated as the final crop. The user can adjust the crop, rotate the image, reset the crop, keep the original, retake, or skip the photo. If no likely rectangle is found, the manual full-image path remains available.

The feature does not perform OCR, perspective correction, cylindrical-label dewarping, automatic shutter control, or image upload. Detection can fail on curved, glossy, low-contrast, or busy backgrounds and can outline a nearby rectangular object; the user must check the captured image and can crop it manually. Images are re-encoded as JPEG to remove embedded metadata. The original and selected crop remain in app-private temporary storage while the label is being reviewed; after the user accepts the label, the chosen image is attached to that bottle draft and temporary capture files are removed.

The Android app currently targets arm64-v8a and x86_64. No image is sent to search or language-model providers.
