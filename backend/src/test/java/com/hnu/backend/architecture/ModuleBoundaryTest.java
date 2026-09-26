package com.hnu.backend.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 固定本次治理的模块边界；其他业务模块的既有依赖环不在本次重构范围内。 */
class ModuleBoundaryTest {
  private static final String ROOT = "com.hnu.backend.";
  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.hnu.backend");

  @Test
  void controllersNeverDependOnMappers() {
    noClasses()
        .that()
        .haveSimpleNameEndingWith("Controller")
        .should()
        .dependOnClassesThat(
            new DescribedPredicate<>("业务持久化 Mapper") {
              @Override
              public boolean test(JavaClass type) {
                return type.getPackageName().startsWith(ROOT)
                    && (type.getPackageName().contains(".mapper")
                        || type.isAnnotatedWith(org.apache.ibatis.annotations.Mapper.class));
              }
            })
        .check(CLASSES);
  }

  @Test
  void documentModuleOnlyUsesKnowledgeBasePublicApi() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + "document..")
        .should()
        .dependOnClassesThat(internalsOf("knowledgebase"))
        .check(CLASSES);
  }

  @Test
  void publicApisDoNotExposeModuleInternals() {
    noClasses()
        .that()
        .resideInAnyPackage(ROOT + "knowledgebase.api..", ROOT + "document.api..")
        .should()
        .dependOnClassesThat(internalsOf("knowledgebase").or(internalsOf("document")))
        .check(CLASSES);
  }

  @Test
  void deletionOrchestrationOnlyUsesPublicModuleApis() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + "application..")
        .should()
        .dependOnClassesThat(internalsOf("knowledgebase").or(internalsOf("document")))
        .check(CLASSES);
  }

  @Test
  void knowledgeBaseCoreDoesNotDependOnDocumentsOrOrchestration() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + "knowledgebase..")
        .and()
        .resideOutsideOfPackage("..controller..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(ROOT + "document..", ROOT + "application..")
        .check(CLASSES);
  }

  @Test
  void governedModuleCoresAreFreeOfCycles() {
    // Controller 是 HTTP 入口，可调用应用编排；核心模块不能反向依赖入口。
    slices()
        .assignedFrom(
            new SliceAssignment() {
              @Override
              public SliceIdentifier getIdentifierOf(JavaClass type) {
                String name = type.getPackageName();
                if (!name.startsWith(ROOT) || name.contains(".controller")) {
                  return SliceIdentifier.ignore();
                }
                String module = name.substring(ROOT.length()).split("\\.")[0];
                return Set.of("knowledgebase", "document", "application").contains(module)
                    ? SliceIdentifier.of(module)
                    : SliceIdentifier.ignore();
              }

              @Override
              public String getDescription() {
                return "知识库、文档与应用编排核心";
              }
            })
        .should()
        .beFreeOfCycles()
        .check(CLASSES);
  }

  @Test
  void moduleCoresNeverDependOnControllers() {
    noClasses()
        .that()
        .resideOutsideOfPackage("..controller..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..controller..")
        .check(CLASSES);
  }

  @Test
  void jsonBuildersAreCentralized() {
    noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + "common.json..")
        .should()
        .callMethod(tools.jackson.databind.json.JsonMapper.class, "builder")
        .check(CLASSES);
  }

  @Test
  void commonDoesNotDependOnBusinessModules() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + "common..")
        .should()
        .dependOnClassesThat(
            new DescribedPredicate<>("公共包之外的项目实现") {
              @Override
              public boolean test(JavaClass type) {
                return type.getPackageName().startsWith(ROOT)
                    && !type.getPackageName().startsWith(ROOT + "common.");
              }
            })
        .check(CLASSES);
  }

  private static DescribedPredicate<JavaClass> internalsOf(String module) {
    return new DescribedPredicate<>(module + " 模块的非公开实现") {
      @Override
      public boolean test(JavaClass type) {
        return type.getPackageName().startsWith(ROOT + module + ".")
            && !type.getPackageName().equals(ROOT + module + ".api")
            && !type.getPackageName().startsWith(ROOT + module + ".api.");
      }
    };
  }
}
