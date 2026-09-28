package com.team1.reservation.calendar.service;

import com.team1.reservation.calendar.dto.ScheduleConstraint;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RegexConstraintParser {

    private static final Pattern AFTER_HOUR = Pattern.compile("(오전|오후)\\s*(\\d{1,2})\\s*시\\s*이후");

    private RegexConstraintParser(){

    }

    public static ScheduleConstraint tryParse(String constraint){
        if (constraint == null || constraint.isBlank()){
            return null;
        }
        Matcher matcher = AFTER_HOUR.matcher(constraint);
        if (!matcher.find()){
            return null;
        }
        String period = matcher.group(1);
        int hour = Integer.parseInt(matcher.group(2));
        if (hour<1 || hour > 12){
            return null;
        }
        return new ScheduleConstraint(toHour24(period,hour), null);
    }

    // 오전 12시는 자정(0시), 오후 12시는 정오(12시)다. 나머지 오후는 12를 더한다.
    private static int toHour24(String period,int hour){
        if ("오후".equals(period)){
            return hour == 12 ? 12 : hour + 12;
        }
        return hour == 12 ? 0 : hour;
    }
}
