# Stage 01 — Repo scaffold

Create the `RegistrationDriftComparison` repository from CPC's furniture, with `oc3d-core` and
`autofix-core` shaded and relocated into the plugin's own namespace, so that `mvn package` produces
one jar that Fiji loads and that shows two placeholder entries under `Plugins > Registration`.

## Why this stage exists

Fifteen later stages add code to a repo that must already build, test, package and install. Getting
the Maven configuration, the two shaded cores and the plugin registration right once — by cloning a
repo that is already published and working — removes a whole class of problem from every stage after
it. CPC is the reference because it is the plugin in this family that got adopted, and its
one-jar-zero-prerequisites build is why.

The shading in particular has a silent failure mode: a wrong Maven coordinate in `<artifactSet>`
matches nothing, the build succeeds, and the jar ships without the core classes. That surfaces as
`NoClassDefFoundError` the first time a user clicks Run. Proving both relocations here, with a real
call through each, is the point of the exit gate.

## Prerequisites

None.

## Read first

- `00_overview.md` — especially the house rules
- `../../../ImageJ Plugins/Registration and Drift Comparison/01_NAMING.md` — **the identity table is
  authoritative for every name below**
- `../../../ImageJ Plugins/Registration and Drift Comparison/02_CONTRACT.md` § *Inherited chassis*
  and § *Chassis-duplication decision*
- `Experiments\CPC\pom.xml` — the model, in full. Lines 151–207 are the shade block copied below
- `Experiments\CPC\src\main\resources\plugins.config`
- `Experiments\CPC\src\main\java\cpc\CPC_.java` — entry-class shape
- `Experiments\CPC\.github\workflows\` — the build workflow
- `Experiments\ImageJ Plugins\Cores\PLUGIN_CORE_PATTERN.md` — the six rules, especially rule 3
- `Experiments\ImageJ Plugins\Cores\autofix-core\README.md` § *Consuming it* — **including "Do not
  add `<minimizeJar>`"**
- `Experiments\ImageJ Plugins\Cores\oc3d-core\README.md` — the package table

Ignore any CPC file with `(Jamie Malcolm's conflicted copy …)` in its name. Those are sync-conflict
artefacts, not source, and copying one produces a build that looks fine and diverges silently.

## Scope

- Create the repo at `Experiments\RegistrationDriftComparison\`, `git init`, branch `main`.
- Clone and adapt CPC's `pom.xml`: parent `org.scijava:pom-scijava:43.0.0`, `groupId`
  `io.github.jay2owe`, `artifactId` `RegistrationDriftComparison`, version `0.1.0-SNAPSHOT`,
  `package-name` property `regdrift`. **`ij` is the only runtime compile dependency**; JUnit
  test-scoped; the two cores compile-scoped and shaded away at package time.
- Copy `mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore`, `.gitattributes`, `LICENSE` (BSD-3-Clause) and
  `src/license/**` from CPC.
- Configure `maven-shade-plugin` with **both** relocations and **no `<minimizeJar>`** — see the
  sketch and the reason below.
- Create the package tree: `regdrift`, `regdrift.diag`, `regdrift.advise`, `regdrift.harness`,
  `regdrift.score`, `regdrift.autofix`, `regdrift.ui`, `regdrift.internal`.
- `src/main/resources/plugins.config` with the two entries.
- Two stub `PlugIn` classes — `CompareRegistration_` and `RegistrationDiagnostics_` — each showing
  an "under construction" message. Stage 04 replaces both.
- One trivial call through each relocated core, so the exit gate can prove the shading worked.
- Clone the GitHub Actions build workflow, adapting names.
- One smoke test so `mvn test` has something to run.
- At the end of the stage, move `docs/regdrift-build/` into the new repo and note it in the commit
  message so later stages know where to look.

## Out of scope

- Any diagnosis, recommendation, harness or scoring logic — stages 06 onward.
- The real dialogs and entry classes — stage 04.
- `CITATION.cff`, `CHANGELOG.md`, `PUBLISHING_AUDIT.md`, the real `README.md` — stage 16.
- Creating the GitHub remote or the update site — stage 16 and the publishing skills.
- Anything in `regdrift.autofix` beyond the empty package — stage 05.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `pom.xml` | NEW | From CPC; identity per `01_NAMING.md`; both relocations |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/*` | NEW | Copied from CPC |
| `.gitignore`, `.gitattributes`, `LICENSE`, `src/license/**` | NEW | Copied from CPC |
| `src/main/resources/plugins.config` | NEW | Two menu entries |
| `src/main/java/regdrift/CompareRegistration_.java` | NEW | Stub entry class |
| `src/main/java/regdrift/RegistrationDiagnostics_.java` | NEW | Stub entry class |
| `src/main/java/regdrift/internal/CoreProbe.java` | NEW | One call into each relocated core, so shading is provable |
| `src/test/java/regdrift/ScaffoldSmokeTest.java` | NEW | Proves the test harness runs and both cores resolve |
| `.github/workflows/build-main.yml` | NEW | Copied from CPC, names adapted |

## Implementation sketch

`src/main/resources/plugins.config` — two entries, plain ASCII, trailing newline, no BOM:

```
Plugins>Registration, "Compare Registration Methods...", regdrift.CompareRegistration_
Plugins>Registration, "Registration Diagnostics...", regdrift.RegistrationDiagnostics_
```

`pom.xml` identity block:

```xml
<parent>
    <groupId>org.scijava</groupId>
    <artifactId>pom-scijava</artifactId>
    <version>43.0.0</version>
</parent>

<groupId>io.github.jay2owe</groupId>
<artifactId>RegistrationDriftComparison</artifactId>
<version>0.1.0-SNAPSHOT</version>
<name>Registration and Drift Comparison</name>
<description>Measures the movement in a time-lapse stack, says whether it can be registered,
recommends a registration engine from measured benchmarks, and scores the result against an
interpolation-matched control.</description>

<properties>
    <package-name>regdrift</package-name>
    <license.licenseName>bsd_3</license.licenseName>
</properties>
```

Dependencies — `ij` compile, the two cores compile-and-shaded, JUnit test:

```xml
<dependencies>
    <dependency>
        <groupId>net.imagej</groupId>
        <artifactId>ij</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.jay2owe</groupId>
        <artifactId>oc3d-core</artifactId>
        <version>0.2.0</version>
    </dependency>
    <dependency>
        <groupId>io.github.jay2owe</groupId>
        <artifactId>autofix-core</artifactId>
        <version>0.1.0</version>
    </dependency>
    <dependency>
        <groupId>junit</groupId>
        <artifactId>junit</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

Both cores declare `net.imagej:ij` as `provided`, and `provided` is **not transitive**, so this
project must declare `ij` itself — it does, above. Neither core is installed in the local Maven
repository by default; run `mvn install` in each core's folder first if resolution fails.

The shade block, copied from `Experiments\CPC\pom.xml:151-207` with two changes — the coordinates,
and `<minimizeJar>` removed:

```xml
<plugin>
    <artifactId>maven-shade-plugin</artifactId>
    <executions>
        <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
                <createDependencyReducedPom>false</createDependencyReducedPom>
                <!--
                  No <minimizeJar>. CPC sets it and is right to. autofix-core forbids it:
                  its entry points are reached through interfaces this plugin implements
                  (SpecCatalogue, Probe, DependencyKey) and its probes are built from a
                  catalogue the minimiser cannot see through, so minimisation would drop
                  classes only ever reached at runtime. Shade minimises per execution, not
                  per artifact, so one forbidding core settles it for both. The cost is
                  ~60 unreachable oc3d-core classes travelling along: jar size, not
                  correctness.
                -->
                <artifactSet>
                    <includes>
                        <include>io.github.jay2owe:oc3d-core</include>
                        <include>io.github.jay2owe:autofix-core</include>
                    </includes>
                </artifactSet>
                <relocations>
                    <relocation>
                        <pattern>sc.fiji.oc3d.core</pattern>
                        <shadedPattern>regdrift.internal.core</shadedPattern>
                    </relocation>
                    <relocation>
                        <pattern>sc.fiji.autofix.core</pattern>
                        <shadedPattern>regdrift.internal.autofix</shadedPattern>
                    </relocation>
                </relocations>
                <filters>
                    <filter>
                        <artifact>*:*</artifact>
                        <excludes>
                            <exclude>META-INF/*.SF</exclude>
                            <exclude>META-INF/*.DSA</exclude>
                            <exclude>META-INF/*.RSA</exclude>
                            <exclude>META-INF/maven/**</exclude>
                        </excludes>
                    </filter>
                </filters>
            </configuration>
        </execution>
    </executions>
</plugin>
```

`<artifactSet>` takes a **Maven coordinate**; `<relocations>` takes a **Java package**. They look
similar and are not interchangeable, and getting the first one wrong fails silently.

Stub entry class — both are identical apart from the title:

```java
package regdrift;

import ij.IJ;
import ij.plugin.PlugIn;

public class CompareRegistration_ implements PlugIn {
    @Override
    public void run(String arg) {
        IJ.showMessage("Compare Registration Methods",
                "Under construction. See docs/regdrift-build/.");
    }
}
```

`CoreProbe` exists only so the smoke test can prove both relocations survived packaging. One call
into each core, chosen because neither needs an image:

```java
package regdrift.internal;

import sc.fiji.oc3d.core.macro.MacroOptions;
import sc.fiji.autofix.core.Product;

/** Proves both shaded cores are present and relocated. Delete when stages 02 and 05 use them for real. */
public final class CoreProbe {
    private CoreProbe() { }

    public static boolean coresPresent() {
        return MacroOptions.class.getName().contains("internal.core")
                || MacroOptions.class.getName().startsWith("sc.fiji");   // unshaded, i.e. during mvn test
    }

    public static String productName() {
        return Product.named("RegistrationDriftComparison").toString();
    }
}
```

Confirm both class names against the cores before writing this — `sc.fiji.oc3d.core.macro.MacroOptions`
and `sc.fiji.autofix.core.Product` are listed in their READMEs, but read the source rather than
trusting the table.

`ToggleSwitch`: `02_CONTRACT.md` says copy it verbatim from CPC. **Prefer the relocated one** at
`sc.fiji.oc3d.core.ui.ToggleSwitch`, which is the same widget already shared by the family. Fall
back to the verbatim copy only if the relocated widget misbehaves under shading, and record which
route was taken in the commit message — stage 04 needs to know.

Build command, matching the family's known-good JDK setup:

```bash
export JAVA_HOME="/c/Users/Owner/OneDrive - Imperial College London/ImageJ/Experiments/First Experiment Round/Combined/Oracle_JDK-23"
bash mvnw clean package -Denforcer.skip=true
```

`-Denforcer.skip=true` is required, as in FLASH and CPC.

## Exit gate

1. `bash mvnw clean package -Denforcer.skip=true` succeeds from the repo root.
2. `target/RegistrationDriftComparison-0.1.0-SNAPSHOT.jar` exists.
3. `unzip -l` on that jar lists classes under **both** `regdrift/internal/core/` and
   `regdrift/internal/autofix/`, and lists **nothing** under `sc/fiji/`. Both halves matter: the
   first proves the shading ran, the second proves the relocation did.
4. `mvn dependency:tree` shows no compile-scope dependency other than `ij`, the two cores and their
   transitives. Grep the output for `mcib3d`, `sc.fiji.`, `trove`, `imglib2` and `net.imagej.updater`
   and confirm each returns nothing.
5. `mvn test` runs and `ScaffoldSmokeTest` passes.
6. Copy the jar into the local Fiji `plugins/` folder, restart Fiji, and confirm **both** entries
   appear under `Plugins ▸ Registration` and show their placeholder messages.
7. `git log` shows one commit; the working tree is clean; branch is `main`.
8. `docs/regdrift-build/` is present inside the new repo.

## Known risks

- **Parent POM mismatch.** Both cores build against `pom-scijava:31.1.0`; this plugin uses `43.0.0`.
  That is fine for a build-time shaded dependency, but the two parents manage different `ij`
  versions. If a core class fails to link at runtime, check which `ij` won the resolution before
  changing anything else.
- **The cores may not be installed locally.** `mvn install` inside `Cores\oc3d-core\` and
  `Cores\autofix-core\` first. Neither is published to a remote repository, by design.
- **`oc3d-core`'s string-constant trap.** It names five tuning and image properties with literals
  beginning `sc.fiji.oc3d.core.`, and shading silently rewrites those literals into
  `regdrift.internal.core.*`. This plugin reads none of them, so it is harmless here — but do not
  add code that reads or writes an `oc3d-core` image property expecting the documented name.
- **`plugins.config` encoding.** Plain ASCII, trailing newline. A BOM makes Fiji skip the entry with
  no error message at all.
- **CPC's conflicted-copy files.** Check every source path before copying.
- **This repo lives inside a synchronized folder.** Run all git commands from the repo root, never
  from the synchronized parent, and note that the user profile is itself a git repo that will shadow
  if the working directory is wrong.
- **Fiji caches jars.** If the menu entries do not appear, remove any older jar from `plugins/`
  before blaming `plugins.config`.
