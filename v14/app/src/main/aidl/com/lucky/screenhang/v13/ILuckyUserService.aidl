package com.lucky.screenhang.v13;

interface ILuckyUserService {
    int screenOff() = 0;
    String status() = 1;
    String getLog() = 2;
    void destroy() = 16777114;
}
