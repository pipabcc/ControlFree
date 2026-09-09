package com.example.controlfree

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.controlfree.ui.main.MainScreen
import com.example.controlfree.ui.main.MainTab
import com.example.controlfree.ui.todo.TodoSubTab
import com.example.controlfree.widget.WidgetPlanNavigationRequest
import com.example.controlfree.widget.WidgetTodoNavigationRequest

@Composable
fun MainNavigation(
  selectedTab: MainTab,
  onTabSelected: (MainTab) -> Unit,
  initialTodoSubTab: TodoSubTab = TodoSubTab.TODO,
  onTodoSubTabSelected: (TodoSubTab) -> Unit = {},
  widgetTodoNavigationRequest: WidgetTodoNavigationRequest? = null,
  onWidgetTodoNavigationConsumed: (WidgetTodoNavigationRequest) -> Unit = {},
  widgetPlanNavigationRequest: WidgetPlanNavigationRequest? = null,
  onWidgetPlanNavigationConsumed: (WidgetPlanNavigationRequest) -> Unit = {},
  isDarkTheme: Boolean,
  onDarkThemeChange: (Boolean) -> Unit,
  bgType: String = "pure",
  bgColor: Int = 0,
  bgGradient: String = "",
  bgImageIndex: Int = -1,
  onBackgroundChanged: (String, Int, String, Int) -> Unit = { _, _, _, _ -> },
  showQuickAddNoteDialog: Boolean = false,
  onShowQuickAddNoteDialogChange: (Boolean) -> Unit = {}
) {
  MainScreen(
    selectedTab = selectedTab,
    onTabSelected = onTabSelected,
    initialTodoSubTab = initialTodoSubTab,
    onTodoSubTabSelected = onTodoSubTabSelected,
    widgetTodoNavigationRequest = widgetTodoNavigationRequest,
    onWidgetTodoNavigationConsumed = onWidgetTodoNavigationConsumed,
    widgetPlanNavigationRequest = widgetPlanNavigationRequest,
    onWidgetPlanNavigationConsumed = onWidgetPlanNavigationConsumed,
    isDarkTheme = isDarkTheme,
    onDarkThemeChange = onDarkThemeChange,
    bgType = bgType,
    bgColor = bgColor,
    bgGradient = bgGradient,
    bgImageIndex = bgImageIndex,
    onBackgroundChanged = onBackgroundChanged,
    showQuickAddNoteDialog = showQuickAddNoteDialog,
    onShowQuickAddNoteDialogChange = onShowQuickAddNoteDialogChange,
    modifier = Modifier.fillMaxSize()
  )
}
