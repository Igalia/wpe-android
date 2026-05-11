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
#include "Logging.h"
#include "WPEInputMethodContextAndroid.h"

#include <wpe/wpe-platform.h>

DECLARE_JNI_CLASS_SIGNATURE(JNIWPEInputMethodContext, "org/wpewebkit/wpe/WPEInputMethodContext");
DECLARE_JNI_CLASS_SIGNATURE(
    JNIWPEInputMethodContextFocusListener, "org/wpewebkit/wpe/WPEInputMethodContext$FocusListenerHolder");

namespace WebKit {

class JNIWPEInputMethodContextFocusListenerCache final : public JNI::TypedClass<JNIWPEInputMethodContextFocusListener> {
public:
    JNIWPEInputMethodContextFocusListenerCache()
        : JNI::TypedClass<JNIWPEInputMethodContextFocusListener>(true)
        , m_onFocusIn(getMethod<void()>("onFocusIn"))
        , m_onFocusOut(getMethod<void()>("onFocusOut"))
    {
    }

    void onFocusIn(JNIWPEInputMethodContextFocusListener listener) const
    {
        try {
            m_onFocusIn.invoke(listener);
        } catch (const std::exception& ex) {
            Logging::logError("cannot call WPEInputMethodContext focus-in callback (%s)", ex.what());
        }
    }

    void onFocusOut(JNIWPEInputMethodContextFocusListener listener) const
    {
        try {
            m_onFocusOut.invoke(listener);
        } catch (const std::exception& ex) {
            Logging::logError("cannot call WPEInputMethodContext focus-out callback (%s)", ex.what());
        }
    }

private:
    // NOLINTBEGIN(cppcoreguidelines-avoid-const-or-ref-data-members)
    const JNI::Method<void()> m_onFocusIn;
    const JNI::Method<void()> m_onFocusOut;
    // NOLINTEND(cppcoreguidelines-avoid-const-or-ref-data-members)
};

const JNIWPEInputMethodContextFocusListenerCache& getJNIFocusListenerCache()
{
    static const JNIWPEInputMethodContextFocusListenerCache s_singleton;
    return s_singleton;
}

namespace {

    struct FocusListenerHolder {
        JNI::GlobalRef<JNIWPEInputMethodContextFocusListener> ref;
    };

    GQuark focusListenerQuark()
    {
        static GQuark quark = g_quark_from_static_string("wpe-im-context-android-focus-listener");
        return quark;
    }

    void destroyFocusListenerHolder(gpointer ptr) noexcept
    {
        delete static_cast<FocusListenerHolder*>(ptr);
    }

    void onFocusInThunk(gpointer userData)
    {
        auto* holder = static_cast<FocusListenerHolder*>(userData);
        if (holder && holder->ref)
            getJNIFocusListenerCache().onFocusIn(holder->ref.get());
    }

    void onFocusOutThunk(gpointer userData)
    {
        auto* holder = static_cast<FocusListenerHolder*>(userData);
        if (holder && holder->ref)
            getJNIFocusListenerCache().onFocusOut(holder->ref.get());
    }

} // namespace

class JNIWPEInputMethodContextCache final : public JNI::TypedClass<JNIWPEInputMethodContext> {
public:
    JNIWPEInputMethodContextCache()
        : JNI::TypedClass<JNIWPEInputMethodContext>(true)
    {
        registerNativeMethods(JNI::NativeMethod<void(jlong, jstring)>("nativeCommitText", nativeCommitText),
            JNI::NativeMethod<void(jlong, jint, jint)>("nativeDeleteSurrounding", nativeDeleteSurrounding),
            JNI::NativeMethod<void(jlong, JNIWPEInputMethodContextFocusListener)>(
                "nativeSetFocusListener", nativeSetFocusListener),
            JNI::StaticNativeMethod<jlong(jlong)>("nativeGetForView", nativeGetForView));
    }

private:
    static void nativeCommitText(JNIEnv* env, jobject, jlong nativePtr, jstring text)
    {
        // GetStringUTFChars returns modified UTF-8 (CESU-8), which mangles supplementary characters
        // like emoji. Go through UTF-16 + g_utf16_to_utf8 to produce standard UTF-8 for WebKit.
        const jchar* chars = env->GetStringChars(text, nullptr);
        const jsize len = env->GetStringLength(text);
        g_autofree gchar* utf8
            = g_utf16_to_utf8(reinterpret_cast<const gunichar2*>(chars), len, nullptr, nullptr, nullptr);
        env->ReleaseStringChars(text, chars);
        wpe_input_method_context_android_commit_text(JNI::from_jlong<WPEInputMethodContextAndroid>(nativePtr), utf8);
    }

    static void nativeDeleteSurrounding(JNIEnv*, jobject, jlong nativePtr, jint offset, jint count)
    {
        wpe_input_method_context_android_delete_surrounding(
            JNI::from_jlong<WPEInputMethodContextAndroid>(nativePtr), offset, static_cast<unsigned int>(count));
    }

    static void nativeSetFocusListener(
        JNIEnv* env, jobject, jlong nativePtr, JNIWPEInputMethodContextFocusListener listener)
    {
        auto* context = JNI::from_jlong<WPEInputMethodContextAndroid>(nativePtr);
        if (listener) {
            auto* holder
                = new FocusListenerHolder {JNI::GlobalRef<JNIWPEInputMethodContextFocusListener>(env, listener)};
            // qdata owns the holder: the destroy notify runs on context dispose and on listener replacement.
            g_object_set_qdata_full(G_OBJECT(context), focusListenerQuark(), holder, destroyFocusListenerHolder);
            wpe_input_method_context_android_set_focus_callbacks(context, onFocusInThunk, onFocusOutThunk, holder);
        } else {
            wpe_input_method_context_android_set_focus_callbacks(context, nullptr, nullptr, nullptr);
            g_object_set_qdata(G_OBJECT(context), focusListenerQuark(), nullptr);
        }
    }

    static jlong nativeGetForView(JNIEnv*, jclass, jlong viewPtr)
    {
        return reinterpret_cast<jlong>(wpe_input_method_context_android_from_view(JNI::from_jlong<WPEView>(viewPtr)));
    }
};

const JNIWPEInputMethodContextCache& getJNIWPEInputMethodContextCache()
{
    static const JNIWPEInputMethodContextCache s_singleton;
    return s_singleton;
}

void configureWPEInputMethodContextJNIMappings()
{
    getJNIFocusListenerCache();
    getJNIWPEInputMethodContextCache();
}

} // namespace WebKit
