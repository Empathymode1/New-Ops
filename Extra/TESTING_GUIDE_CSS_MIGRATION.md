# CSS Migration - Testing & Verification Guide

## Quick Test Checklist

### 1. Compile & Build
- [ ] Project compiles without errors
- [ ] No Maven/Gradle build warnings related to CSS
- [ ] UI module builds successfully

### 2. Launch Application
- [ ] Main window launches without errors
- [ ] All UI panels are visible and properly styled
- [ ] No console errors related to CSS

### 3. Light Theme (Default)
Verify all components render correctly in light theme:

**Main Window:**
- [ ] Title bar visible with correct colors (white/beige background)
- [ ] Buttons (Start All, Stop All, +New Job) visible and styled
- [ ] Bell icon appears with primary text color
- [ ] Theme toggle shows "Dark" option

**Service Management Tab (Jobs):**
- [ ] Job table displays with light background
- [ ] Job detail panel on right has light surface background
- [ ] Job name in bold, dark text
- [ ] Direction label shows with muted color
- [ ] Metric cards (Transfer Stats) styled as light cards with borders

**Dashboard Tab:**
- [ ] Summary cards (Running Jobs, Stopped Jobs, etc.) display with white background
- [ ] Info card styling applied correctly
- [ ] Table rows alternate between light colors

**Credentials Tab:**
- [ ] Credential list styled with light background
- [ ] Labels have correct muted text color
- [ ] Avatar circles have light background with accent color

### 4. Dark Theme
Click the "Dark" toggle and verify all components switch:

**Main Window (Dark):**
- [ ] Background switches to dark gray (#202124)
- [ ] Text switches to light color (#E8EAED)
- [ ] Title bar updates to dark theme colors
- [ ] Theme toggle shows "Light" option

**Service Management Tab (Dark):**
- [ ] Job detail panel has dark surface background
- [ ] Text remains readable (light color)
- [ ] Cards have dark backgrounds with adjusted borders
- [ ] Status indicators remain visible

**Dashboard Tab (Dark):**
- [ ] Summary cards styled with dark backgrounds
- [ ] Table rows use dark theme colors
- [ ] Text contrast maintained

**Notification Panel (Dark):**
- [ ] Notification cards styled with dark theme
- [ ] Read/unread indicators still visible
- [ ] Time labels readable

### 5. Component-Specific Tests

#### Status Badges
- [ ] Light theme: colored pills (watching=tan, transferring=green, error=red, idle=gray)
- [ ] Dark theme: colored pills adjusted for dark background
- [ ] All status colors clearly visible in both themes

#### Toast Notifications
(Trigger by performing actions that generate notifications)
- [ ] Success toast: green background + text
- [ ] Error toast: red background + text
- [ ] Warning toast: yellow background + text
- [ ] Info toast: blue background + text
- [ ] Light theme: bright colors
- [ ] Dark theme: muted colors with sufficient contrast

#### Job Edit Dialog
(Click "+ New Job" or edit existing job)
- [ ] Dialog background matches current theme
- [ ] Form labels use correct text color
- [ ] Input fields have border and background color
- [ ] Buttons (Save, Cancel) styled correctly
- [ ] Dialog updates instantly when switching themes

#### Health Panel
(Click "Service Health" tab)
- [ ] Connection status label appears (Connected/Disconnected)
- [ ] Connected state: green text, bold
- [ ] Disconnected state: red text, bold
- [ ] Labels update when toggling theme

#### Filter Toolbar
(Visible above job table)
- [ ] Background color matches current theme
- [ ] Labels ("Event Type:", "Job:") readable
- [ ] ComboBoxes styled appropriately
- [ ] Border visible in both themes

### 6. Theme Toggle Test
Rapidly toggle between light and dark themes:

- [ ] All components update smoothly
- [ ] No flickering or visual artifacts
- [ ] Colors update immediately
- [ ] No components missing or misaligned after toggle

### 7. Edge Cases

#### Notifications with Unread Count
- [ ] Bell icon turns red when unread notifications exist
- [ ] Bell icon returns to normal color when notifications viewed
- [ ] Color change works in both light and dark themes

#### Dynamic Component Updates
(Perform actions that update components)
- [ ] Job status changes update pill colors
- [ ] Direction labels show correct colors
- [ ] Connection status labels update correctly
- [ ] All updates respect current theme

#### Window Resize
- [ ] Styles remain consistent when resizing
- [ ] No color bleeding or style breaking
- [ ] Layout adapts properly

### 8. Verification Points for Each File

**StatusBadge.java**
- [ ] Pills display with correct status color
- [ ] Color matches light/dark theme variant

**InfoCard.java**
- [ ] Card background matches current theme
- [ ] Title, value, description text colors correct
- [ ] Border visible in both themes

**FilterToolbar.java**
- [ ] Toolbar background color correct
- [ ] Labels readable
- [ ] ComboBoxes styled consistently

**ToastNotification.java**
- [ ] Toast type colors apply correctly
- [ ] Toast disappears after 3 seconds
- [ ] Styling respects current theme

**JobDetailPanel.java**
- [ ] Main container styled correctly
- [ ] Direction label shows with direction color
- [ ] Metric cards have proper styling
- [ ] Configuration section readable

**NotificationPanel.java**
- [ ] Panel background matches theme
- [ ] Read/unread cards differentiated visually
- [ ] Text readable in both themes
- [ ] Buttons properly styled

**JobEditDialog.java**
- [ ] Dialog appears with correct background
- [ ] All form fields visible and styled
- [ ] Buttons (Save/Cancel) clickable and styled
- [ ] Dialog theme-aware

**HealthPanel.java**
- [ ] Connection status shows with correct color
- [ ] Label text readable
- [ ] Color changes with theme toggle

**CredentialsPanel.java**
- [ ] Credential list styled
- [ ] Labels and text readable
- [ ] Empty state message styled

**DashboardPanel.java**
- [ ] Table cells styled correctly
- [ ] Error cells show red text
- [ ] Normal cells show muted text

**MainWindow.java**
- [ ] Root container styled
- [ ] Bell button state changes correctly
- [ ] All tabs display properly
- [ ] Layout responsive

---

## Common Issues & Troubleshooting

### Issue: Components don't change color when toggling theme
**Solution:** Ensure stylesheet is being loaded. Check:
```java
scene.getStylesheets().add(url.toExternalForm()); // in MainWindow.applyTheme()
```

### Issue: Text is unreadable in dark theme
**Solution:** Check that text fill colors use `-fw-text-primary` CSS variable which has different values for light/dark.

### Issue: Buttons not responding to CSS
**Solution:** Ensure buttons use `getStyleClass().add()` rather than `setStyle()` for colors.

### Issue: Colors partially updating
**Solution:** Clear previous style classes when applying new ones:
```java
component.getStyleClass().clear();
component.getStyleClass().add("new-class");
```

### Issue: CSS changes not visible
**Solution:** 
1. Rebuild the project
2. Clear JavaFX CSS cache if needed
3. Restart the application

---

## Performance Notes

- No performance degradation expected from CSS classes vs inline styles
- Stylesheet loading is cached by JavaFX
- Dynamic class changes are efficient
- No animation lag when toggling themes

---

## Rollback Instructions

If needed to rollback to inline styles:

1. Revert CSS files to remove new classes
2. Restore Java files to use `setStyle()` instead of `getStyleClass().add()`
3. Re-add inline color concatenation in methods like `toastNotification()`, etc.

---

**Test Date:** ________________  
**Tester Name:** ________________  
**Result:** PASS / FAIL  
**Notes:** ____________________________


