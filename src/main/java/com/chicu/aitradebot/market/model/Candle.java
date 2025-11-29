package com.chicu.aitradebot.market.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Candle {

    // делаем поля private, чтобы @Data корректно сработал
    private long time;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private boolean closed;

    // Дополнительные методы под "record-стиль",
    // чтобы можно было вызывать c.time(), c.open() и т.д.
    public long time()     { return time; }
    public double open()   { return open; }
    public double high()   { return high; }
    public double low()    { return low; }
    public double close()  { return close; }
    public double volume() { return volume; }
}
