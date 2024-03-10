package com.wpe.wpe;

public class WKCookieManager {
    public enum CookieAcceptPolicy {
        AcceptAlways(0),
        AcceptNever(1),
        AcceptNoThirdParty(2);

        private final int value;

        CookieAcceptPolicy(int value) { this.value = value; }

        private int getValue() { return value; }
    }

    protected long nativePtr;

    private CookieAcceptPolicy cookieAcceptPolicy = CookieAcceptPolicy.AcceptNoThirdParty;

    WKCookieManager(long nativePtr) { this.nativePtr = nativePtr; }

    public CookieAcceptPolicy getCookieAcceptPolicy() { return cookieAcceptPolicy; }

    public void setCookieAcceptPolicy(CookieAcceptPolicy policy) {
        cookieAcceptPolicy = policy;
        nativeSetCookieAcceptPolicy(nativePtr, policy.getValue());
    }

    private static native void nativeSetCookieAcceptPolicy(long nativePtr, int policy);
}
