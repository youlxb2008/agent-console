package com.youlxb2008.admin.utils;

import java.util.HashMap;
import java.util.Map;

import org.springframework.data.domain.Page;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.youlxb2008.admin.utils.page.BPage;

import cn.hutool.json.JSONUtil;


public class ResultVO {
    private Object data; 
    private String code;
    private String msg;
    private boolean success;

    public Object getData() {
        return data;
    }

    public ResultVO setData(Object data) {
        if (data instanceof IPage) {
            this.data = BPage.of((IPage<?>) data);
        } else if (data instanceof Page) {
            this.data = BPage.of((Page<?>) data);
        } else {
            this.data = data;
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    public ResultVO put(String key, Object value) {
        // 如果需要动态加字段，用 Map
        if (this.data == null || !(this.data instanceof Map)) {
            this.data = new HashMap<>();
        }
        ((Map<String, Object>) this.data).put(key, value);
        return this;
    }

	public String getCode() {
		return this.code;
	}

	public ResultVO setCode(String code) {
		this.code = code;
		return this;
	}

	public String getMsg() {
		return this.msg;
	}

	public ResultVO setMsg(String msg) {
		this.msg = msg;
		return this;
	}

	public boolean getSuccess() {
		return this.success;
	}

	public ResultVO setSuccess(boolean isSuccess) {
		this.success = isSuccess;
		return this;
	}

	public String toString() {
		return JSONUtil.toJsonStr(this);
	}
	
	public String toJSON() {
		return JSONUtil.toJsonStr(this);
	}

	public static ResultVO getSuccessVO() {
		return new ResultVO().setSuccess(true).setCode("200");
	}
	
	public static ResultVO getSuccessVO(String msg) {
		return getSuccessVO().setMsg(msg);
	}

	public static ResultVO getFailedVO() {
		return new ResultVO().setSuccess(false).setCode("500");
	}

	public static ResultVO getFailedVO(String msg) {
		return getFailedVO().setMsg(msg);
	}
	
}
