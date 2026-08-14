package com.ensp.nms.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ConfigHealthOverview {
    private int deviceTotal;
    private int neverBackedUp;
    private int staleOverDays;
    private int scheduleFailed;
    private int staleDays = 7;
    private List<DeviceBackupHealth> devices = new ArrayList<>();
}
