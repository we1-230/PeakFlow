package com.zhou.peakflow.exception;

import com.zhou.peakflow.enums.ResultCode;
import com.zhou.peakflow.pojo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器（统一出口：可预期错误 WARN 不打堆栈，系统错误 ERROR 打堆栈）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 当前环境：dev / test 下把异常类名带回响应，便于自测定位 */
    @Value("${spring.profiles.active:dev}")
    private String profile;

    /** 业务异常：自己代码 throw 的，属于可预期错误 */
    @ExceptionHandler(BusinessException.class)
    public Result<Object> handleBusinessException(BusinessException e) {
        log.warn("业务异常：message={}, code={}", e.getMessage(), e.getCode(), e);
        if (e.getData() != null) {
            return Result.error(e.getCode(), e.getMessage(), e.getData());
        }
        return Result.error(e.getCode(), e.getMessage());
    }

    /** JSON 请求体校验失败（@Valid），返回字段级错误，便于前端定位 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException e) {
        Map<String, String> errors = collectFieldErrors(e.getBindingResult().getAllErrors());
        log.warn("参数校验失败（JSON 请求体）：errors={}", errors);
        return Result.error(ResultCode.PARAM_VALID_FAIL.getCode(),
                ResultCode.PARAM_VALID_FAIL.getMessage(), errors);
    }

    /** 表单参数绑定失败，返回字段级错误 */
    @ExceptionHandler(BindException.class)
    public Result<Map<String, String>> handleBindException(BindException e) {
        Map<String, String> errors = collectFieldErrors(e.getBindingResult().getAllErrors());
        log.warn("参数绑定失败（表单）：errors={}", errors);
        return Result.error(ResultCode.PARAM_ERROR.getCode(), ResultCode.PARAM_ERROR.getMessage(), errors);
    }

    /** 缺少必填请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<String> handleMissingParams(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数：paramName={}", e.getParameterName());
        return Result.error(ResultCode.PARAM_MISSING.getCode(),
                String.format("缺少必要参数：%s", e.getParameterName()));
    }

    /** 参数类型转换失败（如路径变量该是数字却传了字母） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<String> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = String.format("参数%s类型错误，期望类型：%s",
                e.getName(), e.getRequiredType().getSimpleName());
        log.warn("参数类型错误：msg={}", message);
        return Result.error(ResultCode.PARAM_FORMAT_ERROR.getCode(), message);
    }

    /** 请求体 JSON 格式非法，无法反序列化 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<String> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体反序列化失败：msg={}", e.getMessage());
        return Result.error(ResultCode.PARAM_FORMAT_ERROR.getCode(), "请求体格式错误");
    }

    /** 响应序列化失败（一般是返回对象里有无法序列化的字段） */
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public Result<String> handleHttpMessageNotWritable(HttpMessageNotWritableException e) {
        log.warn("响应序列化失败：msg={}", e.getMessage());
        return Result.error(ResultCode.PARAM_FORMAT_ERROR.getCode(), "响应体格式错误");
    }

    /** 请求方法不支持（如用 GET 调了 POST 接口） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<String> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持：method={}", e.getMethod());
        return Result.error(HttpStatus.METHOD_NOT_ALLOWED.value(),
                String.format("不支持的请求方法：%s", e.getMethod()));
    }

    /** 接口不存在（404） */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<String> handleNotFound(NoHandlerFoundException e) {
        log.warn("接口不存在：method={}, url={}", e.getHttpMethod(), e.getRequestURL());
        return Result.error(ResultCode.NOT_FOUND);
    }

    /** 数据库唯一约束冲突（子类，必须排在通用数据库异常之前才会被优先匹配） */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public Result<String> handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        log.warn("数据库唯一约束冲突：msg={}", e.getMessage());
        return Result.error(ResultCode.DB_ERROR.getCode(), "操作失败，该数据已存在，请勿重复操作");
    }

    /** 通用数据库异常（连接失败、SQL 错误等），属系统错误，必须打堆栈 */
    @ExceptionHandler({SQLException.class, DataAccessException.class})
    public Result<String> handleDatabaseException(Exception e) {
        log.error("数据库操作异常：", e);
        return Result.error(ResultCode.DB_ERROR);
    }

    /** 权限不足（@PreAuthorize 未通过） */
    @ExceptionHandler(AccessDeniedException.class)
    public Result<String> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("权限拒绝：msg={}", e.getMessage());
        return Result.error(ResultCode.FORBIDDEN);
    }

    /** 认证失败（账号不存在或密码错误），统一文案避免账号枚举 */
    @ExceptionHandler(AuthenticationException.class)
    public Result<String> handleAuthenticationException(AuthenticationException e) {
        log.warn("认证失败：msg={}", e.getMessage());
        return Result.error(ResultCode.UNAUTHORIZED.getCode(), "账号或密码错误");
    }

    /** 缺少必填请求头（最常见是 Authorization） */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public Result<String> handleMissingRequestHeaderException(MissingRequestHeaderException e) {
        String headerName = e.getHeaderName();
        log.warn("缺少请求头：headerName={}", headerName);
        if ("Authorization".equals(headerName)) {
            return Result.error(ResultCode.UNAUTHORIZED, "请先登录");
        }
        return Result.error(ResultCode.PARAM_MISSING, "缺少必要请求头：" + headerName);
    }

    /** 上传文件超过大小限制 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<String> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("上传文件超限：msg={}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR.getCode(), "文件大小超过限制，最大支持 2MB");
    }

    /** 兜底：未预期的系统异常，必须打堆栈；dev/test 附带异常类名便于自测 */
    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        log.error("系统异常（兜底）：", e);
        if ("dev".equals(profile) || "test".equals(profile)) {
            return Result.error(ResultCode.SYSTEM_ERROR.getCode(),
                    "系统繁忙，请稍后重试（" + e.getClass().getSimpleName() + "）");
        }
        return Result.error(ResultCode.SYSTEM_ERROR);
    }

    /** 把校验错误列表转成「字段名 → 提示」的 Map */
    private Map<String, String> collectFieldErrors(java.util.List<org.springframework.validation.ObjectError> allErrors) {
        Map<String, String> errors = new HashMap<>();
        allErrors.forEach(error -> errors.put(((FieldError) error).getField(), error.getDefaultMessage()));
        return errors;
    }
}
