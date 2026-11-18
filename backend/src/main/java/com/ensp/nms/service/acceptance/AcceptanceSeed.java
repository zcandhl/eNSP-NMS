package com.ensp.nms.service.acceptance;

import com.ensp.nms.entity.AcceptancePlan;
import com.ensp.nms.entity.AcceptanceProject;
import com.ensp.nms.entity.Role;
import com.ensp.nms.entity.User;
import com.ensp.nms.repository.AcceptancePlanRepository;
import com.ensp.nms.repository.AcceptanceProjectRepository;
import com.ensp.nms.repository.RoleRepository;
import com.ensp.nms.repository.UserRepository;
import com.ensp.nms.service.RbacSeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 首次启动种子：若系统中没有任何验收工程，创建示例工程、配套方案与队员账号。
 *
 * 只种"配置数据"（工程信息、方案检查项、队员账号——这些本来就该由使用者录入），
 * 绝不种任何测试结果/批次/报告——实测数据必须来自真实采集。
 *
 * @Order(20)：必须晚于 RBAC 种子（@Order(0)）。否则抓到的 OPERATOR 角色是 null，
 * 队员账号会以"无角色、无权限"入库，登录后什么都打不开。
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class AcceptanceSeed implements ApplicationRunner {

    private final AcceptanceProjectRepository projectRepository;
    private final AcceptancePlanRepository planRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final RbacSeedService rbacSeedService;

    /**
     * 示例工程开关：默认关闭——工程/方案属于使用者的业务数据，必须由人录入。
     * 置 true（演示/培训环境）时才会在"库里没有任何工程"时创建一套示例；
     * 用户删掉示例后重启不会复活（因为删完库又空了也只受此开关约束——开关默认关）。
     */
    @Value("${acceptance.seed-demo-data:false}")
    private boolean seedDemoData;

    /** 四名参赛队员的系统账号：检查项 owner 绑定到 username，登录后界面显示"我"负责的项 */
    private static final String[][] MEMBERS = {
            {"player1", "1号·网络设计与实施"},
            {"player2", "2号·软件开发"},
            {"player3", "3号·测试与交付"},
            {"player4", "4号·验收质检"}
    };

    @Override
    public void run(ApplicationArguments args) {
        seedMembers();
        if (!seedDemoData) {
            return;
        }
        if (!projectRepository.findAll().isEmpty()) {
            return;
        }
        AcceptanceProject project = new AcceptanceProject();
        project.setName("三期工程综合实训大楼");
        project.setBuilding("综合实训大楼（6 层 / 约 3.5 万㎡）");
        project.setOwner("学校信息中心");
        project.setContractor("网络承建单位");
        project.setAcceptanceOrg("信息中心");
        project.setNote("5F 13 间机房 650 工位，1—4F、6F 为普通实训室");
        projectRepository.save(project);

        AcceptancePlan plan = new AcceptancePlan();
        plan.setProjectId(project.getId());
        plan.setName("新建园区网验收 V1");
        plan.setVersion("2026.1");
        plan.setStatus("active");
        plan.setChecksJson(toJson(AcceptanceDefaults.standardChecks("player1")));
        planRepository.save(plan);
        log.info("验收种子数据已创建：工程「{}」+ 方案「{}」", project.getName(), plan.getName());
    }

    /** 队员账号已存在则跳过；新账号授予 OPERATOR 角色，初始口令 Player@123，首次登录不强制改密（赛场环境） */
    private void seedMembers() {
        // 兜底：即使被别的 Bean 抢先执行，这里也保证 RBAC 已建好，不拿到 null 角色
        rbacSeedService.ensureRbacSeeded();
        Role operatorRole = roleRepository.findByName("OPERATOR").orElse(null);
        if (operatorRole == null) {
            log.warn("未找到 OPERATOR 角色，队员账号将无法获得权限（请检查 RBAC 种子）");
        }
        for (String[] m : MEMBERS) {
            var existing = userRepository.findByUsername(m[0]);
            if (existing.isPresent()) {
                repairMissingRole(existing.get(), operatorRole);
                continue;
            }
            User u = new User();
            u.setUsername(m[0]);
            u.setPassword(passwordEncoder.encode("Player@123"));
            u.setRealName(m[1]);
            u.setEmail(m[0] + "@ensp.local");
            u.setStatus("active");
            u.setMustChangePassword(false);
            if (operatorRole != null) {
                u.setRoles(Set.of(operatorRole));
            }
            userRepository.save(u);
            log.info("已创建队员账号：{}（{}）", m[0], m[1]);
        }
    }

    /**
     * 修复历史库里的"空角色"队员账号（早期版本因种子顺序问题建成了无角色用户）。
     * 只补空缺，不动已有角色——避免覆盖管理员手工调整。
     */
    private void repairMissingRole(User user, Role operatorRole) {
        if (operatorRole == null || (user.getRoles() != null && !user.getRoles().isEmpty())) {
            return;
        }
        Set<Role> roles = new HashSet<>();
        roles.add(operatorRole);
        user.setRoles(roles);
        userRepository.save(user);
        log.info("已修复队员账号角色：{} → OPERATOR", user.getUsername());
    }

    private String toJson(Object v) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(v);
        } catch (Exception e) {
            return "[]";
        }
    }
}
