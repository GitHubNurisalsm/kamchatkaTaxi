package ru.taksi.tariff;

import java.io.Serializable;
import java.util.Objects;

/** Составной ключ (город, код) для тарифов и опций: ECONOM в Бишкеке и в Москве — разные строки. */
public class CityScopedId implements Serializable {

    private String cityCode;
    private String code;

    protected CityScopedId() {
    }

    public CityScopedId(String cityCode, String code) {
        this.cityCode = cityCode;
        this.code = code;
    }

    public String getCityCode() {
        return cityCode;
    }

    public String getCode() {
        return code;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CityScopedId other
                && Objects.equals(cityCode, other.cityCode)
                && Objects.equals(code, other.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cityCode, code);
    }
}
