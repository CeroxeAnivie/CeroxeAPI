package top.ceroxe.api.utils;

import java.util.Calendar;
import java.util.concurrent.CopyOnWriteArrayList;

public class TimeUtils {
    public static Calendar calendar = Calendar.getInstance();

    private TimeUtils() {
    }

    public static Integer getCurrentYear() {
        calendar = Calendar.getInstance();
        return calendar.get(Calendar.YEAR);
    }

    public static Integer getCurrentMonth() {
        calendar = Calendar.getInstance();
        return calendar.get(Calendar.MONTH) + 1;
    }

    public static Integer getCurrentDay() {
        calendar = Calendar.getInstance();
        return calendar.get(Calendar.DAY_OF_MONTH);
    }

    public static Integer getCurrentHour() {
        calendar = Calendar.getInstance();
        return calendar.get(Calendar.HOUR_OF_DAY);
    }

    public static Integer getCurrentMinutes() {
        calendar = Calendar.getInstance();
        return calendar.get(Calendar.MINUTE);
    }

    public static String getCurrentSecondAsString() {
        calendar = Calendar.getInstance();
        int c = calendar.get(Calendar.SECOND);
        if (String.valueOf(c).length() == 1) {
            StringBuilder s = new StringBuilder("0");
            return s.append(c).toString();
        } else {
            return String.valueOf(c);
        }
    }

    public static Integer getCurrentSecond() {
        calendar = Calendar.getInstance();
        return calendar.get(Calendar.SECOND);
    }

    public static Integer getCurrentMicroseconds() {
        calendar = Calendar.getInstance();
        return calendar.get(Calendar.MILLISECOND);
    }

    public static Long getCurrentTimeAsLong() {
        StringBuilder year = new StringBuilder(TimeUtils.getCurrentYear().toString());
        String month = TimeUtils.getCurrentMonth().toString();
        String day = TimeUtils.getCurrentDay().toString();
        String hour = TimeUtils.getCurrentHour().toString();
        String minutes = TimeUtils.getCurrentMinutes().toString();
        String second = TimeUtils.getCurrentSecondAsString();
        String micros = TimeUtils.getCurrentMicroseconds().toString();
        return Long.valueOf(year.append(month).append(day).append(hour).append(minutes).append(second).append(micros).toString());
    }

    public static String getCurrentTimeAsString() {
        return String.valueOf(TimeUtils.getCurrentTimeAsLong());
    }

    public static CopyOnWriteArrayList<String> getCurrentTimeAsStringListDivided() {
        CopyOnWriteArrayList<String> c = new CopyOnWriteArrayList<>();
        c.add(String.valueOf(TimeUtils.getCurrentYear()));
        c.add(String.valueOf(TimeUtils.getCurrentMonth()));
        c.add(String.valueOf(TimeUtils.getCurrentDay()));
        c.add(String.valueOf(TimeUtils.getCurrentHour()));
        c.add(String.valueOf(TimeUtils.getCurrentMinutes()));
        c.add(String.valueOf(TimeUtils.getCurrentSecond()));
        c.add(String.valueOf(TimeUtils.getCurrentMicroseconds()));
        return c;
    }

    public static CopyOnWriteArrayList<String> getCurrentTimeAsStringList() {
        CopyOnWriteArrayList<String> c = new CopyOnWriteArrayList<>();
        StringBuilder s = new StringBuilder();
        StringBuilder b = new StringBuilder();
        c.add(s.append(TimeUtils.getCurrentYear()).append("/").append(TimeUtils.getCurrentMonth()).append("/").append(TimeUtils.getCurrentDay()).toString());
        c.add(b.append(TimeUtils.getCurrentHour()).append(":").append(TimeUtils.getCurrentMinutes()).append(":").append(TimeUtils.getCurrentSecond()).toString());
        c.add(String.valueOf(TimeUtils.getCurrentMicroseconds()));
        return c;
    }

    public static String getCurrentTimeAsFileName(boolean includeMicrosecond) {
        StringBuilder year = new StringBuilder(TimeUtils.getCurrentYear().toString());
        String month = TimeUtils.getCurrentMonth().toString();
        String day = TimeUtils.getCurrentDay().toString();
        String hour = TimeUtils.getCurrentHour().toString();
        String minutes = TimeUtils.getCurrentMinutes().toString();
        String second = TimeUtils.getCurrentSecondAsString();
        String micros = TimeUtils.getCurrentMicroseconds().toString();
        if (includeMicrosecond) {
            return year.append("-").append(month).append("-").append(day).append("-").append(hour).append("-").append(minutes).append("-").append(second).append("-").append(micros).toString();
        } else {
            return year.append("-").append(month).append("-").append(day).append("-").append(hour).append("-").append(minutes).append("-").append(second).toString();
        }
    }

    public static String getCurrentTimeAsFileName() {
        return TimeUtils.getCurrentTimeAsFileName(false);
    }
//    public static int standardTimeFileNameToIntTime(){
//
//    }

}