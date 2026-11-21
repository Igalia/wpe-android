/**
 * Copyright (C) 2026 Igalia S.L.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#include "WebKitImageBitmap.h"

#include "Logging.h"

#include <algorithm>
#include <android/bitmap.h>
#include <limits>
#include <vector>

DECLARE_JNI_CLASS_SIGNATURE(JNIBitmapConfig, "android/graphics/Bitmap$Config");

namespace WebKit {

// Makes the bitmaps of icons, as Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) does.
class JNIBitmapCache final : public JNI::TypedClass<JNIBitmap> {
public:
    JNIBitmapCache()
        : JNI::TypedClass<JNIBitmap>(true)
        , m_createBitmap(getStaticMethod<JNIBitmap(jint, jint, JNIBitmapConfig)>("createBitmap"))
        , m_argb8888(JNI::getCurrentThreadJNIEnv(),
              JNI::TypedClass<JNIBitmapConfig>().getStaticField<JNIBitmapConfig>("ARGB_8888").getValue().get())
    {
    }

    // Null, with the exception logged and cleared, if Java cannot make it, as when it is out of memory.
    JNI::ProtectedType<JNIBitmap> createBitmap(jint width, jint height) const
    {
        return m_createBitmap.invoke(width, height, m_argb8888.get());
    }

private:
    // NOLINTBEGIN(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::StaticMethod<JNIBitmap(jint, jint, JNIBitmapConfig)> m_createBitmap;
    const JNI::GlobalRef<JNIBitmapConfig> m_argb8888;
    // NOLINTEND(cppcoreguidelines-avoid-const-or-ref-data-members)
};

static const JNIBitmapCache& getJNIBitmapCache()
{
    static const JNIBitmapCache s_singleton;
    return s_singleton;
}

bool isValidImageLayout(int width, int height, size_t stride, size_t sourceSize)
{
    if (width <= 0 || height <= 0 || static_cast<size_t>(width) > std::numeric_limits<size_t>::max() / 4)
        return false;

    const size_t rowBytes = static_cast<size_t>(width) * 4;
    if (stride < rowBytes || sourceSize < rowBytes)
        return false;

    // Check the last row without overflowing stride * height. Its trailing padding is optional.
    return static_cast<size_t>(height - 1) <= (sourceSize - rowBytes) / stride;
}

// WebKit provides BGRA; Android RGBA_8888 bitmaps store RGBA bytes. Both are premultiplied, so only the channel
// order changes.
static void storeAsRGBA(uint8_t* destination, uint8_t blue, uint8_t green, uint8_t red, uint8_t alpha)
{
    destination[0] = red;
    destination[1] = green;
    destination[2] = blue;
    destination[3] = alpha;
}

// Call only after validating the source layout with isValidImageLayout().
static void copyImagePixels(
    const uint8_t* source, int width, int height, size_t sourceStride, uint8_t* destination, size_t destinationStride)
{
    const size_t rowBytes = static_cast<size_t>(width) * 4;
    for (int y = 0; y < height; ++y) {
        const auto* src = source + (static_cast<size_t>(y) * sourceStride);
        auto* dst = destination + (static_cast<size_t>(y) * destinationStride);
        for (size_t x = 0; x < rowBytes; x += 4)
            storeAsRGBA(dst + x, src[x], src[x + 1], src[x + 2], src[x + 3]);
    }
}

ImageSize scaledImageSize(int width, int height, int maxWidth, int maxHeight)
{
    const int64_t limitWidth = maxWidth > 0 ? maxWidth : width;
    const int64_t limitHeight = maxHeight > 0 ? maxHeight : height;
    if (width <= limitWidth && height <= limitHeight)
        return {width, height};

    // The tighter limit sets the scale. The other side is rounded to the nearest pixel, and is at least one.
    if (width * limitHeight >= height * limitWidth)
        return {static_cast<int>(limitWidth), std::max(1, static_cast<int>((height * limitWidth + width / 2) / width))};
    return {std::max(1, static_cast<int>((width * limitHeight + height / 2) / height)), static_cast<int>(limitHeight)};
}

// Scales the image down by averaging the source pixels that fall in each destination pixel. The pixels are
// premultiplied, so a plain average of each channel is correct. Call only after validating the source layout with
// isValidImageLayout(), and with a size from scaledImageSize().
static void copyScaledImagePixels(const uint8_t* source, int width, int height, size_t sourceStride,
    uint8_t* destination, ImageSize scaled, size_t destinationStride)
{
    // The source columns of destination column x are columns[x] up to, but not including, columns[x + 1].
    std::vector<int> columns(static_cast<size_t>(scaled.width) + 1);
    for (int x = 0; x <= scaled.width; ++x)
        columns[x] = static_cast<int>(static_cast<int64_t>(x) * width / scaled.width);

    std::vector<uint64_t> sums(static_cast<size_t>(scaled.width) * 4);
    for (int y = 0; y < scaled.height; ++y) {
        const int firstRow = static_cast<int>(static_cast<int64_t>(y) * height / scaled.height);
        const int endRow = static_cast<int>(static_cast<int64_t>(y + 1) * height / scaled.height);
        std::fill(sums.begin(), sums.end(), 0);
        for (int row = firstRow; row < endRow; ++row) {
            const auto* src = source + (static_cast<size_t>(row) * sourceStride);
            for (int x = 0; x < scaled.width; ++x) {
                for (int column = columns[x]; column < columns[x + 1]; ++column) {
                    const auto* pixel = src + (static_cast<size_t>(column) * 4);
                    for (int channel = 0; channel < 4; ++channel)
                        sums[(static_cast<size_t>(x) * 4) + channel] += pixel[channel];
                }
            }
        }

        auto* dst = destination + (static_cast<size_t>(y) * destinationStride);
        for (int x = 0; x < scaled.width; ++x) {
            const uint64_t count = static_cast<uint64_t>(endRow - firstRow) * (columns[x + 1] - columns[x]);
            const auto* sum = &sums[static_cast<size_t>(x) * 4];
            auto average = [count](uint64_t total) { return static_cast<uint8_t>((total + count / 2) / count); };
            storeAsRGBA(
                dst + (static_cast<size_t>(x) * 4), average(sum[0]), average(sum[1]), average(sum[2]), average(sum[3]));
        }
    }
}

bool fillBitmap(
    JNIEnv* env, jobject bitmap, const uint8_t* source, int width, int height, size_t sourceStride, ImageSize scaled)
{
    AndroidBitmapInfo info {};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS
        || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width != static_cast<uint32_t>(scaled.width)
        || info.height != static_cast<uint32_t>(scaled.height) || info.stride < static_cast<size_t>(scaled.width) * 4) {
        Logging::logError("unexpected bitmap layout");
        return false;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        Logging::logError("cannot lock bitmap pixels");
        return false;
    }
    auto* destination = static_cast<uint8_t*>(pixels);
    // Without scaling, the plain copy gives the same pixels as copyScaledImagePixels(), only faster.
    if (scaled.width == width && scaled.height == height)
        copyImagePixels(source, width, height, sourceStride, destination, info.stride);
    else
        copyScaledImagePixels(source, width, height, sourceStride, destination, scaled, info.stride);
    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

WebKitImage* imageForSize(WebKitImageList* images, int size)
{
    WebKitImage* best = nullptr;
    int bestSide = 0;
    const gsize length = images ? webkit_image_list_get_length(images) : 0;
    for (gsize i = 0; i < length; ++i) {
        auto* image = webkit_image_list_get(images, i);
        const int side = std::max(webkit_image_get_width(image), webkit_image_get_height(image));
        // A smaller image that is still big enough, or a bigger one while none is. Of two of the same size, the later,
        // as WebKit adds the icons a page declares after those it had, which it keeps.
        if (!best || (side >= size && (bestSide < size || side <= bestSide)) || (bestSide < size && side >= bestSide)) {
            best = image;
            bestSide = side;
        }
    }
    return best;
}

JNI::ProtectedType<JNIBitmap> createIconBitmap(WebKitImage* image, int size)
{
    if (!image)
        return {};

    const int width = webkit_image_get_width(image);
    const int height = webkit_image_get_height(image);
    const size_t stride = webkit_image_get_stride(image);
    GBytes* bytes = webkit_image_as_bytes(image);
    gsize dataSize = 0;
    const auto* data = static_cast<const uint8_t*>(bytes ? g_bytes_get_data(bytes, &dataSize) : nullptr);
    if (!data || !isValidImageLayout(width, height, stride, dataSize)) {
        Logging::logError("invalid WebKit icon buffer");
        return {};
    }

    const auto scaled = scaledImageSize(width, height, size, size);
    auto bitmap = getJNIBitmapCache().createBitmap(scaled.width, scaled.height);
    if (!bitmap || !fillBitmap(JNI::getCurrentThreadJNIEnv(), bitmap.get(), data, width, height, stride, scaled))
        return {};
    return bitmap;
}

} // namespace WebKit
