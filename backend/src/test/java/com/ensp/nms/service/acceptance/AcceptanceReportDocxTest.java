package com.ensp.nms.service.acceptance;

import com.ensp.nms.entity.AcceptanceBatch;
import com.ensp.nms.entity.AcceptancePlan;
import com.ensp.nms.entity.AcceptanceProject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验收报告 docx 装配测试：用样例批次数据渲染一份 Word，
 * 校验 OOXML 结构可解压、关键章节文本齐全，并把样例落盘到 target/ 供人工打开查看。
 */
class AcceptanceReportDocxTest {

    private final AcceptanceReportService service = new AcceptanceReportService(
            null, null, null, mockUserService(), new ObjectMapper());

    /** 用户服务桩：报告"验收组织"章节展示负责人真实姓名 */
    private static com.ensp.nms.service.UserService mockUserService() {
        com.ensp.nms.service.UserService svc = org.mockito.Mockito.mock(com.ensp.nms.service.UserService.class);
        org.mockito.Mockito.when(svc.getAllUsers()).thenReturn(List.of());
        return svc;
    }

    @Test
    void renderDocx_shouldProduceValidWordWithAllChapters() throws Exception {
        AcceptanceProject project = new AcceptanceProject();
        project.setName("综合实训大楼网络工程");
        project.setBuilding("实训楼 A 座（6 层）");
        project.setOwner("四川三河职业学院");
        project.setContractor("XX 网络集成有限公司");
        project.setAcceptanceOrg("学校信息中心");
        project.setDeviceCount(12);
        project.setNote("含汇聚、接入、无线三个子系统");

        AcceptancePlan plan = new AcceptancePlan();
        plan.setName("园区网标准六项验收");
        plan.setVersion("1.2");
        plan.setChecksJson("""
                [
                  {"code":"A-01","title":"设备可达性","owner":"张三","severity":"high","enabled":true,"assertExpr":"reachable == 12"},
                  {"code":"A-02","title":"OSPF 邻居","owner":"李四","severity":"high","enabled":true,"assertExpr":"full > 0"}
                ]
                """);

        AcceptanceBatch batch = new AcceptanceBatch();
        batch.setBatchId("B-001");
        batch.setNo("YS-20261109-001");
        batch.setStatus("DONE");
        batch.setStartedAt(LocalDateTime.of(2026, 11, 9, 21, 0, 0));
        batch.setFinishedAt(LocalDateTime.of(2026, 11, 9, 21, 3, 42));
        batch.setStatusMessage("");
        batch.setItemsJson("""
                [
                  {"code":"A-01","title":"设备可达性","owner":"张三","status":"PASS","durationMs":1200,
                   "metrics":{"reachable":12,"total":12},"message":"12 台设备全部可达",
                   "rawOutput":"PINGS OK\\nall 12 reachable"},
                  {"code":"A-02","title":"OSPF 邻居","owner":"李四","status":"FINDING","durationMs":800,
                   "metrics":{"full":2,"total":3},"message":"存在未 FULL 邻居",
                   "findings":[{"device":"SW-ACC-03","detail":"与核心邻居停留在 EXSTART","severity":"high"}],
                   "rawOutput":"OSPF NEIGHBORS\\n..."}
                ]
                """);

        byte[] docx = service.renderDocx(project, plan, batch, new ObjectMapper().readValue(
                batch.getItemsJson(), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                }));

        // 1) 是 zip（docx 本质是 zip）
        assertEquals(0x50, docx[0] & 0xFF);
        assertEquals(0x4B, docx[1] & 0xFF);

        // 2) document.xml 解得出来且关键章节齐全（九章 + 附录 A）
        String documentXml = readEntry(docx, "word/document.xml");
        for (String expected : new String[]{
                "测 试 验 收 报 告", "YS-20261109-001", "一、验收依据", "二、工程概况",
                "三、验收组织与人员分工", "四、测试方法与环境说明", "五、验收项清单与判定准则",
                "六、分项测试结果", "6.2  逐项判定说明",
                "七、问题清单", "建议整改期限",
                "八、验收结论与统计", "有条件通过", "九、签字栏", "附录 A  采集原始记录（备查）",
                "设备可达性", "OSPF 邻居", "发现问题（待整改）", "P-001", "SW-ACC-03",
                "eNSP 模拟环境", "full > 0"}) {
            assertTrue(documentXml.contains(expected), "文档缺少关键内容：" + expected);
        }

        // 2.1) 页眉受控标识与页脚页码（header/footer 是独立 part）
        boolean hasHeader = entryNames(docx).stream().anyMatch(n -> n.matches("word/header\\d+\\.xml"));
        boolean hasFooter = entryNames(docx).stream().anyMatch(n -> n.matches("word/footer\\d+\\.xml"));
        assertTrue(hasHeader, "docx 缺少页眉（受控标识）");
        assertTrue(hasFooter, "docx 缺少页脚（页码）");
        String headerXml = readFirst(docx, "word/header\\d+\\.xml");
        assertTrue(headerXml.contains("文件编号"), "页眉缺少文件编号");
        String footerXml = readFirst(docx, "word/footer\\d+\\.xml");
        assertTrue(footerXml.contains("PAGE"), "页脚缺少页码域");

        // 3) 落盘供人工打开（人工抽查版式）
        Path out = Path.of("target", "acceptance-report-sample.docx");
        Files.write(out, docx);
        System.out.println("样例报告已生成：" + out.toAbsolutePath());
    }

    private String readEntry(byte[] docx, String name) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (name.equals(e.getName())) {
                    // XML 实体反转义：表达式里的 >= < & 在 document.xml 中会被转义
                    return new String(zin.readAllBytes(), StandardCharsets.UTF_8)
                            .replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&");
                }
            }
        }
        throw new AssertionError("docx 中找不到 " + name);
    }

    private java.util.List<String> entryNames(byte[] docx) throws Exception {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
    }

    /** 按正则取第一个匹配 entry 的内容（页眉/页脚文件名带序号） */
    private String readFirst(byte[] docx, String regex) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().matches(regex)) {
                    return new String(zin.readAllBytes(), StandardCharsets.UTF_8)
                            .replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&");
                }
            }
        }
        throw new AssertionError("docx 中找不到匹配 " + regex + " 的条目");
    }
}
