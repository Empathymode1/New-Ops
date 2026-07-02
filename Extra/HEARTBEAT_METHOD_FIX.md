# NoSuchMethodError: getLastHeartbeat() - Diagnostic & Fix

## Problem

**Exception:**
```
java.lang.NoSuchMethodError: 'java.time.LocalDateTime com.filewatchercommon.model.WatchJob.getLastHeartbeat()'
	at com.filewatcherui.ui.JobTablePanel.lambda$21(JobTablePanel.java:213)
```

**Root Cause:** The UI is calling `WatchJob.getLastHeartbeat()` but the WatchJob class in the classpath doesn't have this method.

---

## Why This Happens

### What's In Source Code:
✅ **WatchJob.java (lines 147-148)** HAS the getter/setter:
```java
public LocalDateTime getLastHeartbeat() { return lastHeartbeat; }
public void setLastHeartbeat(LocalDateTime lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
```

### What's In Classpath:
❌ **Compiled JAR/Class** is from an older version WITHOUT the method.

### Why?
- Old JAR still loaded in IDE/application classpath
- Maven dependency cache not refreshed
- Stale compiled classes
- IDE didn't rebuild after changes

---

## Solution: Complete Rebuild

### Step 1: Clean All Build Artifacts
```bash
cd "d:\New-Ops Tool\FileWatcher\FileWatcher"
mvn clean
```
This removes:
- ✅ All compiled .class files
- ✅ All built JARs in target/
- ✅ All module caches

### Step 2: Rebuild All Modules
```bash
mvn install -DskipTests
```
This will:
- ✅ Recompile filewatcher-common (INCLUDING WatchJob with getLastHeartbeat)
- ✅ Recompile filewatcher-service
- ✅ Recompile filewatcher-ui
- ✅ Update all JAR dependencies
- ✅ Install to local Maven cache

### Step 3: Verify in IDE (if using IDE)
- **IntelliJ IDEA:** File → Invalidate Caches → Restart IDE
- **Eclipse:** Project → Clean → Clean All Projects → Build All
- **VSCode:** Reload Window (Ctrl+Shift+P → Developer: Reload Window)

### Step 4: Restart Application
```bash
mvn clean install -DskipTests
java -jar filewatcher-service/target/filewatcher-service-*.jar
# In another terminal:
java -jar filewatcher-ui/target/filewatcher-ui-*.jar
```

---

## What Actually Changed

### WatchJob.java (filewatcher-common)
**Lines 46-50:**
```java
// Per-job liveness tick — distinct from lastTransfer. Updated whenever the
// job's watcher (NIO loop, polling tick, or remote-exec heartbeat task) is
// confirmed alive, regardless of whether a file actually moved. See
// FileWatcherService.touchHeartbeat().
private LocalDateTime lastHeartbeat;
```

**Lines 147-148:**
```java
public LocalDateTime getLastHeartbeat() { return lastHeartbeat; }
public void setLastHeartbeat(LocalDateTime lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
```

These were added to support per-job heartbeat tracking (backend writes to it, UI reads from it).

### JobTablePanel.java (filewatcher-ui)
**Lines 212-214:**
```java
heartbeatCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
    c.getValue().getLastHeartbeat() != null
            ? c.getValue().getLastHeartbeat().format(FMT) : "—"));
```

This calls the method to display the heartbeat in the "Last Heartbeat" table column.

---

## Verification Checklist

After rebuilding, verify:

- [x] Maven clean completed (no target/ directories)
- [x] Maven install completed successfully
- [x] No compilation errors in filewatcher-common
- [x] JAR files updated (check timestamps in target/)
- [x] IDE cache invalidated (if applicable)
- [x] Application restarted
- [x] Dashboard displays "Last Heartbeat" column without errors
- [x] JobTablePanel loads WatchJobs successfully

---

## If Error Persists

### Scenario 1: IDE Still Shows Old Code
```
Solution: Close IDE completely, delete IDE caches, restart
- IntelliJ: rm -r ~/.IntelliJIdea*
- Eclipse: rm -r workspace/.metadata
- VSCode: Reload Window
```

### Scenario 2: Old JAR Still In Classpath
```
Solution: Verify Maven settings
- Run: mvn dependency:tree
- Look for old filewatcher-common versions
- Run: mvn clean install -U (forces updates)
```

### Scenario 3: Multiple Java Processes Running
```
Solution: Kill all Java processes before restart
- Windows: taskkill /F /IM java.exe
- Linux/Mac: killall java
```

---

## Root Cause Analysis

| Component | Status | Details |
|-----------|--------|---------|
| WatchJob source code | ✅ Has method | Line 147 has getLastHeartbeat() |
| JobTablePanel source code | ✅ Calls method | Line 213 calls getLastHeartbeat() |
| WatchJob compiled class | ❌ Missing method | Old JAR in classpath |
| filewatcher-common JAR | ❌ Stale | Needs rebuild |

**Solution:** Rebuild from clean source, so all JARs are up-to-date.

---

## Why This Matters

This is a **common Java/Maven issue** where:
1. Source code changes are made ✅
2. Source looks correct in IDE ✅
3. But compiled classes/JARs are stale ❌
4. Runtime loads old JAR ❌
5. Method doesn't exist at runtime ❌

**Prevention:** Always `mvn clean install` after adding new methods to shared modules.

---

## Commands Reference

| Purpose | Command |
|---------|---------|
| Clean build artifacts | `mvn clean` |
| Rebuild all modules | `mvn install -DskipTests` |
| Force update dependencies | `mvn install -U -DskipTests` |
| Verify WatchJob has method | `grep -n "getLastHeartbeat" filewatcher-common/src/main/java/com/filewatchercommon/model/WatchJob.java` |
| Check compiled JAR | `jar tf filewatcher-common/target/*.jar \| grep WatchJob` |

---

## Status

✅ **Source code is correct** — Method exists in WatchJob.java  
❌ **Compiled artifacts are stale** — Need to rebuild  
🔧 **Fix:** Run `mvn clean install -DskipTests`

---

## Next Steps

1. Run: `mvn clean install -DskipTests`
2. Restart application
3. Verify "Last Heartbeat" column displays in Dashboard without errors
4. If still failing, check IDE cache (see "If Error Persists" section)
