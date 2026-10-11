package com.minedisplay.protocol;

/** 协议异常：任何不符（版本/类型/长度/校验）都抛这个。 */
public final class ProtocolException extends Exception {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
