package com.chexuan.mtt.common;

import lombok.Data;

/**
 * 统一响应（与主服 BaseResponse 结构一致：code 200=成功）
 */
@Data
public class BaseResponse<T> {

    private Integer code;
    private String message;
    private T data;

    public static <T> BaseResponse<T> success(T data) {
        BaseResponse<T> r = new BaseResponse<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> BaseResponse<T> success(String message, T data) {
        BaseResponse<T> r = success(data);
        r.message = message;
        return r;
    }

    public static <T> BaseResponse<T> error(int code, String message) {
        BaseResponse<T> r = new BaseResponse<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
