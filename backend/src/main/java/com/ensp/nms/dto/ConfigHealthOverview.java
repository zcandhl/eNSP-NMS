package com.ensp.nms.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置备份健康度汇总视图对象。
 *
 * @author  ZC
 * @version 1.0.0
 * @since   17
 * @date    2026-08-15
 */

@Data
public class ConfigHealthOverview {
    private int deviceTotal;
    private int neverBackedUp;
    private int staleOverDays;
    private int scheduleFailed;
    private int staleDays = 7;
    private List<DeviceBackupHealth> devices = new ArrayList<>();
}
