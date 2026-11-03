package com.zhou.peakflow.exception;

import com.zhou.peakflow.enums.ResultCode;
import lombok.Getter;

/**
 * 可预期的业务异常：携带业务错误码，由全局异常处理器统一转为 Result
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    private final String message;

    private final Object data;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.BUSINESS_ERROR.getCode();
        this.message = message;
        this.data = null;
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
        this.data = null;
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
        this.message = message;
        this.data = null;
    }

    public BusinessException(ResultCode resultCode, Object data) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
        this.data = data;
    }
}