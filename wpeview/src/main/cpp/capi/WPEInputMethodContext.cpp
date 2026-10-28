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

#include "JNI/JNI.h"
#include "JNIMappings.h"
#include "Logging.h"
#include "WPEInputMethodContextAndroid.h"

#include <string>
#include <vector>
#include <wpe/wpe-platform.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWPEInputMethodContext, "org/wpewebkit/wpe/WPEInputMethodContext");
DECLARE_JNI_CLASS_SIGNATURE(JNIWPEInputMethodContextListener, "org/wpewebkit/wpe/WPEInputMethodContext$ListenerHolder");
DECLARE_JNI_CLASS_SIGNATURE(
    JNIWPEInputMethodContextSurroundingListener, "org/wpewebkit/wpe/WPEInputMethodContext$SurroundingListenerHolder");

namespace WebKit {

class JNIWPEInputMethodContextListenerCache final : public JNI::TypedClass<JNIWPEInputMethodContextListener> {
public:
    JNIWPEInputMethodContextListenerCache()
        : JNI::TypedClass<JNIWPEInputMethodContextListener>(true)
        , m_onFocusIn(getMethod<void()>("onFocusIn"))
        , m_onFocusOut(getMethod<void()>("onFocusOut"))
        , m_onPreeditReset(getMethod<void()>("onPreeditReset"))
    {
    }

    void onFocusIn(JNIWPEInputMethodContextListener listener) const
    {
        if (!m_onFocusIn.invoke(listener))
            Logging::logError("cannot call WPEInputMethodContext focus-in callback");
    }

    void onFocusOut(JNIWPEInputMethodContextListener listener) const
    {
        if (!m_onFocusOut.invoke(listener))
            Logging::logError("cannot call WPEInputMethodContext focus-out callback");
    }

    void onPreeditReset(JNIWPEInputMethodContextListener listener) const
    {
        if (!m_onPreeditReset.invoke(listener))
            Logging::logError("cannot call WPEInputMethodContext preedit-reset callback");
    }

private:
    // NOLINTBEGIN(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void()> m_onFocusIn;
    const JNI::Method<void()> m_onFocusOut;
    const JNI::Method<void()> m_onPreeditReset;
    // NOLINTEND(cppcoreguidelines-avoid-const-or-ref-data-members)
};

static const JNIWPEInputMethodContextListenerCache& getJNIListenerCache()
{
    static const JNIWPEInputMethodContextListenerCache s_singleton;
    return s_singleton;
}

class JNIWPEInputMethodContextSurroundingListenerCache final
    : public JNI::TypedClass<JNIWPEInputMethodContextSurroundingListener> {
public:
    JNIWPEInputMethodContextSurroundingListenerCache()
        : JNI::TypedClass<JNIWPEInputMethodContextSurroundingListener>(true)
        , m_onSurroundingChanged(getMethod<void(jstring, jint, jint)>("onSurroundingChanged"))
    {
    }

    void onSurroundingChanged(
        JNIWPEInputMethodContextSurroundingListener listener, jstring text, jint cursor, jint anchor) const
    {
        if (!m_onSurroundingChanged.invoke(listener, text, cursor, anchor))
            Logging::logError("cannot call WPEInputMethodContext surrounding callback");
    }

private:
    // NOLINTNEXTLINE(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void(jstring, jint, jint)> m_onSurroundingChanged;
};

static const JNIWPEInputMethodContextSurroundingListenerCache& getJNISurroundingListenerCache()
{
    static const JNIWPEInputMethodContextSurroundingListenerCache s_singleton;
    return s_singleton;
}

namespace {

    struct ListenerHolder {
        JNI::GlobalRef<JNIWPEInputMethodContextListener> ref;
    };

    GQuark listenerQuark()
    {
        static GQuark quark = g_quark_from_static_string("wpe-im-context-android-listener");
        return quark;
    }

    void destroyListenerHolder(gpointer ptr) noexcept
    {
        delete static_cast<ListenerHolder*>(ptr);
    }

    void onFocusInThunk(gpointer userData)
    {
        auto* holder = static_cast<ListenerHolder*>(userData);
        if (holder && holder->ref)
            getJNIListenerCache().onFocusIn(holder->ref.get());
    }

    void onFocusOutThunk(gpointer userData)
    {
        auto* holder = static_cast<ListenerHolder*>(userData);
        if (holder && holder->ref)
            getJNIListenerCache().onFocusOut(holder->ref.get());
    }

    void onPreeditResetThunk(gpointer userData)
    {
        auto* holder = static_cast<ListenerHolder*>(userData);
        if (holder && holder->ref)
            getJNIListenerCache().onPreeditReset(holder->ref.get());
    }

    // GetStringUTFChars returns modified UTF-8 (CESU-8), which mangles supplementary characters
    // like emoji. Go through UTF-16 + g_utf16_to_utf8 to produce standard UTF-8 for WebKit.
    // g_utf16_to_utf8 fails on an unpaired surrogate (an input method can split an emoji), so
    // replace those with U+FFFD first rather than lose the whole text.
    gchar* toUTF8(JNIEnv* env, jstring text)
    {
        const jsize len = env->GetStringLength(text);
        // g_utf16_to_utf8 gives NULL for an empty buffer, whose data() may be null.
        if (!len)
            return g_strdup("");
        std::vector<gunichar2> units(static_cast<size_t>(len));
        env->GetStringRegion(text, 0, len, reinterpret_cast<jchar*>(units.data()));
        auto isLead = [](gunichar2 unit) { return unit >= 0xD800 && unit <= 0xDBFF; };
        auto isTrail = [](gunichar2 unit) { return unit >= 0xDC00 && unit <= 0xDFFF; };
        for (size_t i = 0; i < units.size(); ++i) {
            if (isLead(units[i]) && i + 1 < units.size() && isTrail(units[i + 1]))
                ++i;
            else if (isLead(units[i]) || isTrail(units[i]))
                units[i] = 0xFFFD;
        }
        return g_utf16_to_utf8(units.data(), len, nullptr, nullptr, nullptr);
    }

    struct SurroundingListenerHolder {
        JNI::GlobalRef<JNIWPEInputMethodContextSurroundingListener> ref;
    };

    GQuark surroundingListenerQuark()
    {
        static GQuark quark = g_quark_from_static_string("wpe-im-context-android-surrounding-listener");
        return quark;
    }

    void destroySurroundingListenerHolder(gpointer ptr) noexcept
    {
        delete static_cast<SurroundingListenerHolder*>(ptr);
    }

    struct SurroundingTextUTF16 {
        std::u16string text;
        jint cursor = 0;
        jint anchor = 0;
    };

    // Android counts text positions in UTF-16 code units (Java chars), and WebKit in bytes of UTF-8. Converts
    // the text and both indices in one pass, since WebKit sends the whole field on every change. An index that
    // falls inside a character is moved back to the start of that character.
    bool convertSurroundingToUTF16(
        const char* text, unsigned length, unsigned cursorIndex, unsigned selectionIndex, SurroundingTextUTF16& result)
    {
        result.text.reserve(length);
        for (unsigned i = 0;; i = g_utf8_next_char(text + i) - text) {
            if (cursorIndex >= i)
                result.cursor = static_cast<jint>(result.text.size());
            if (selectionIndex >= i)
                result.anchor = static_cast<jint>(result.text.size());
            if (i >= length)
                break;

            // Validated one character at a time: a field can hold U+0000, which g_utf8_validate() takes for the
            // end of the text, and g_utf8_validate_len() and g_utf8_get_char_validated() reject.
            gunichar character = text[i] ? g_utf8_get_char_validated(text + i, length - i) : 0;
            if (character == static_cast<gunichar>(-1) || character == static_cast<gunichar>(-2))
                return false;
            if (character >= 0x10000) {
                character -= 0x10000;
                result.text.push_back(static_cast<char16_t>(0xD800 + (character >> 10)));
                result.text.push_back(static_cast<char16_t>(0xDC00 + (character & 0x3FF)));
            } else
                result.text.push_back(static_cast<char16_t>(character));
        }
        return true;
    }

    void onSurroundingThunk(
        const char* text, unsigned length, unsigned cursorIndex, unsigned selectionIndex, gpointer userData)
    {
        auto* holder = static_cast<SurroundingListenerHolder*>(userData);
        if (!holder || !holder->ref)
            return;

        if (!text) {
            getJNISurroundingListenerCache().onSurroundingChanged(holder->ref.get(), nullptr, 0, 0);
            return;
        }

        SurroundingTextUTF16 surrounding;
        if (!convertSurroundingToUTF16(text, length, cursorIndex, selectionIndex, surrounding)) {
            Logging::logError("WPEInputMethodContext surrounding text is not valid UTF-8");
            return;
        }

        // Not JNI::String, which takes UTF-8: the indices have to be in UTF-16 units anyway, so the text is
        // converted along with them and handed over as UTF-16.
        auto* env = JNI::getCurrentThreadJNIEnv();
        jstring localText = env->NewString(
            reinterpret_cast<const jchar*>(surrounding.text.data()), static_cast<jsize>(surrounding.text.size()));
        if (localText == nullptr) {
            JNI::clearJavaException(env);
            Logging::logError("Cannot create a Java string for the surrounding text");
            return;
        }

        auto jText = JNI::String(std::move(localText));
        getJNISurroundingListenerCache().onSurroundingChanged(
            holder->ref.get(), static_cast<jstring>(jText), surrounding.cursor, surrounding.anchor);
    }

} // namespace

class JNIWPEInputMethodContextCache final : public JNI::TypedClass<JNIWPEInputMethodContext> {
public:
    JNIWPEInputMethodContextCache()
        : JNI::TypedClass<JNIWPEInputMethodContext>(true)
    {
        registerNativeMethods(JNI::NativeMethod<void(jlong, jstring)>("nativeCommitText", nativeCommitText),
            JNI::NativeMethod<void(jlong, jint, jint)>("nativeDeleteSurrounding", nativeDeleteSurrounding),
            JNI::NativeMethod<void(jlong, jstring, jint)>("nativeSetPreedit", nativeSetPreedit),
            JNI::NativeMethod<void(jlong)>("nativeFinishPreedit", nativeFinishPreedit),
            JNI::NativeMethod<void(jlong, JNIWPEInputMethodContextListener)>("nativeSetListener", nativeSetListener),
            JNI::NativeMethod<void(jlong, JNIWPEInputMethodContextSurroundingListener)>(
                "nativeSetSurroundingListener", nativeSetSurroundingListener),
            JNI::StaticNativeMethod<jlong(jlong)>("nativeGetForView", nativeGetForView));
    }

private:
    static void nativeCommitText(JNIEnv* env, jobject, jlong nativePtr, jstring text)
    {
        g_autofree gchar* utf8 = toUTF8(env, text);
        wpe_input_method_context_android_commit_text(JNI::from_jlong<WPEInputMethodContextAndroid>(nativePtr), utf8);
    }

    static void nativeSetPreedit(JNIEnv* env, jobject, jlong nativePtr, jstring text, jint cursorOffset)
    {
        g_autofree gchar* utf8 = toUTF8(env, text);
        wpe_input_method_context_android_set_preedit(
            JNI::from_jlong<WPEInputMethodContextAndroid>(nativePtr), utf8, static_cast<unsigned int>(cursorOffset));
    }

    static void nativeFinishPreedit(JNIEnv*, jobject, jlong nativePtr)
    {
        wpe_input_method_context_android_finish_preedit(JNI::from_jlong<WPEInputMethodContextAndroid>(nativePtr));
    }

    static void nativeDeleteSurrounding(JNIEnv*, jobject, jlong nativePtr, jint offset, jint count)
    {
        wpe_input_method_context_android_delete_surrounding(
            JNI::from_jlong<WPEInputMethodContextAndroid>(nativePtr), offset, static_cast<unsigned int>(count));
    }

    static void nativeSetListener(JNIEnv* env, jobject, jlong nativePtr, JNIWPEInputMethodContextListener listener)
    {
        auto* context = JNI::from_jlong<WPEInputMethodContextAndroid>(nativePtr);
        if (listener) {
            auto* holder = new ListenerHolder {JNI::GlobalRef<JNIWPEInputMethodContextListener>(env, listener)};
            // qdata owns the holder: the destroy notify runs on context dispose and on listener replacement.
            g_object_set_qdata_full(G_OBJECT(context), listenerQuark(), holder, destroyListenerHolder);
            wpe_input_method_context_android_set_focus_callbacks(context, onFocusInThunk, onFocusOutThunk, holder);
            wpe_input_method_context_android_set_reset_callback(context, onPreeditResetThunk, holder);
        } else {
            wpe_input_method_context_android_set_focus_callbacks(context, nullptr, nullptr, nullptr);
            wpe_input_method_context_android_set_reset_callback(context, nullptr, nullptr);
            g_object_set_qdata(G_OBJECT(context), listenerQuark(), nullptr);
        }
    }

    static void nativeSetSurroundingListener(
        JNIEnv* env, jobject, jlong nativePtr, JNIWPEInputMethodContextSurroundingListener listener)
    {
        auto* context = JNI::from_jlong<WPEInputMethodContextAndroid>(nativePtr);
        if (listener) {
            auto* holder = new SurroundingListenerHolder {
                JNI::GlobalRef<JNIWPEInputMethodContextSurroundingListener>(env, listener)};
            // qdata owns the holder: the destroy notify runs on context dispose and on listener replacement.
            g_object_set_qdata_full(
                G_OBJECT(context), surroundingListenerQuark(), holder, destroySurroundingListenerHolder);
            wpe_input_method_context_android_set_surrounding_callback(context, onSurroundingThunk, holder);
        } else {
            wpe_input_method_context_android_set_surrounding_callback(context, nullptr, nullptr);
            g_object_set_qdata(G_OBJECT(context), surroundingListenerQuark(), nullptr);
        }
    }

    static jlong nativeGetForView(JNIEnv*, jclass, jlong viewPtr)
    {
        return reinterpret_cast<jlong>(wpe_input_method_context_android_from_view(JNI::from_jlong<WPEView>(viewPtr)));
    }
};

static const JNIWPEInputMethodContextCache& getJNIWPEInputMethodContextCache()
{
    static const JNIWPEInputMethodContextCache s_singleton;
    return s_singleton;
}

void configureWPEInputMethodContextJNIMappings()
{
    getJNIListenerCache();
    getJNISurroundingListenerCache();
    getJNIWPEInputMethodContextCache();
}

} // namespace WebKit
