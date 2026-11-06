package com.lw.homenas;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.HashSet;
import java.util.Set;

/** Selection rules describe all pages; explicit day choices override month/all rules. */
final class MediaSelection {
    final boolean all,onlyPending;
    final String kind;
    final Set<String> days,months,excludedDays,excludedMonths;
    MediaSelection(JSONObject input) throws Exception {
        all=input.optBoolean("all");onlyPending=input.optBoolean("only_pending");kind=input.optString("kind","all");
        if(!kind.matches("all|image|video"))throw new IllegalArgumentException("媒体类型无效");
        days=values(input,"days",false);months=values(input,"months",true);
        excludedDays=values(input,"exclude_days",false);excludedMonths=values(input,"exclude_months",true);
    }
    private static Set<String> values(JSONObject input,String key,boolean month) throws Exception {
        JSONArray list=input.optJSONArray(key);Set<String> result=new HashSet<>();if(list==null)return result;
        if(list.length()>10000)throw new IllegalArgumentException("日期选择过多，请分批选择");
        for(int i=0;i<list.length();i++){String value=list.getString(i);if(!value.equals("unknown")){
            if(!value.matches(month?"[0-9]{4}-(0[1-9]|1[0-2])":"[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])"))throw new IllegalArgumentException("日期无效");
            java.text.SimpleDateFormat format=new java.text.SimpleDateFormat(month?"yyyy-MM":"yyyy-MM-dd",java.util.Locale.ROOT);format.setLenient(false);format.parse(value);
        }result.add(value);}return result;
    }
    boolean includes(String date){String month=date.equals("unknown")?date:date.substring(0,7);return days.contains(date)||(!excludedDays.contains(date)&&!excludedMonths.contains(month)&&(all||months.contains(month)));}
    boolean hasRange(){return all||!days.isEmpty()||!months.isEmpty();}
}
