package com.youlxb2008.admin.utils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;

public class DateUtils {
	/** 时间格式(yyyy-MM-dd) */
	public static final String FORMAT_YYYY_MM_SS= "yyyy-MM-dd";
	/** 时间格式(yyyy-MM-dd HH:mm:ss) */
	public final static String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

	public final static String FORMAT_YYYYMMDD = "yyyyMMdd";
	public final static String FORMAT_YYYYMMDD_HHMMSS = "yyyyMMddHHmmss";
	public static Date parseDate(String date, String format) {
		if (StringUtils.isNotBlank(date)){
			SimpleDateFormat sdf = new SimpleDateFormat(format);
			try {
				return sdf.parse(date);
			} catch (ParseException e) {
				throw new IllegalArgumentException("格式转换异常");
			}
		}
		return null;
	}

	public static String format(Date date, String formatStr) {
		if (date == null) {
			return null;
		}

		SimpleDateFormat sf = new SimpleDateFormat(formatStr);
		return sf.format(date);
	}

	public static String getCurrentDate(String formatStr) {
		return format(new Date(), formatStr);
	}
}