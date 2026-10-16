package com.zhou.peakflow.pojo;

import com.zhou.peakflow.enums.ResultCode;
import lombok.Data;

/**
 * 统一响应结果封装
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> {

    // 业务状态码，200 表示成功
    private Integer code;

    // 提示信息
    private String message;

    // 业务数据
    private T data;

    // 响应时间戳（毫秒）
    private long timestamp;

    private Result() {
        this.timestamp = System.currentTimeMillis();
    }

    // ---- 成功响应 ----

    public static <T> Result<T> success() {
        Result<T> result = new Result<>();
        result.setCode(ResultCode.SUCCESS.getCode());
        result.setMessage(ResultCode.SUCCESS.getMessage());
        return result;
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(ResultCode.SUCCESS.getCode());
        result.setMessage(ResultCode.SUCCESS.getMessage());
        result.setData(data);
        return result;
    }

    public static <T> Result<T> success(String message, T data) {
        Result<T> result = new Result<>();
        result.setCode(ResultCode.SUCCESS.getCode());
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    public static <T> Result<T> success(String message) {
        Result<T> result = new Result<>();
        result.setCode(ResultCode.SUCCESS.getCode());
        result.setMessage(message);
        return result;
    }

    // ---- 错误响应 ----

    public static <T> Result<T> error(ResultCode resultCode) {
        Result<T> result = new Result<>();
        result.setCode(resultCode.getCode());
        result.setMessage(resultCode.getMessage());
        return result;
    }

    public static <T> Result<T> error(ResultCode resultCode, String message) {
        Result<T> result = new Result<>();
        result.setCode(resultCode.getCode());
        result.setMessage(message);
        return result;
    }

    // 业务失败（指定消息，使用通用业务错误码 3005）
    public static <T> Result<T> error(String message) {
        return error(ResultCode.BUSINESS_ERROR.getCode(), message);
    }

    public static <T> Result<T> error(String message, T data) {
        return error(ResultCode.BUSINESS_ERROR.getCode(), message, data);
    }

    public static <T> Result<T> error(Integer code, String message, T data) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> error(ResultCode resultCode, T data) {
        Result<T> result = new Result<>();
        result.setCode(resultCode.getCode());
        result.setMessage(resultCode.getMessage());
        result.setData(data);
        return result;
    }

    // ---- 快捷方法 ----

    public boolean isSuccess() {
        // 使用 equals 比较包装类型，避免 Integer 缓存范围外的 == 误判
        return ResultCode.SUCCESS.getCode().equals(this.code);
    }

    public boolean isError() {
        return !isSuccess();
    }
}