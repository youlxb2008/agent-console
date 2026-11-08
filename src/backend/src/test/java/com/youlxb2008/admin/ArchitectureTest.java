package com.youlxb2008.admin;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分层架构校验测试
 * 确保 Controller / Manager / Service / Mapper 的依赖规则不被破坏
 */
class ArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setup() {
        importedClasses = new ClassFileImporter()
                .importPackages("com.youlxb2008.admin");
    }

    @Test
    @DisplayName("Controller 只能依赖 Manager 和 Service，不能依赖 Mapper")
    void controllerShouldOnlyDependOnManagerAndService() {
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..controller..")
                .should().dependOnClassesThat()
                .resideInAPackage("..mapper..")
                .because("Controller 不应直接依赖 Mapper，应通过 Service 或 Manager 访问数据")
                .check(importedClasses);
    }

    @Test
    @DisplayName("Manager 不能依赖 Controller")
    void managerShouldNotDependOnController() {
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..manager..")
                .should().dependOnClassesThat()
                .resideInAPackage("..controller..")
                .because("Manager 不应依赖 Controller")
                .check(importedClasses);
    }

    @Test
    @DisplayName("Service 不能依赖 Controller")
    void serviceShouldNotDependOnController() {
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat()
                .resideInAPackage("..controller..")
                .because("Service 不应依赖 Controller")
                .check(importedClasses);
    }

    @Test
    @DisplayName("Mapper 不能依赖 Service 或 Manager")
    void mapperShouldNotDependOnServiceOrManager() {
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..mapper..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..service..", "..manager..")
                .because("Mapper 是最底层，不应依赖上层")
                .check(importedClasses);
    }
}
