package sh.siava.pixelxpert.modpacks.systemui;

import static de.robv.android.xposed.XposedBridge.hookAllMethods;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findFieldIfExists;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.modpacks.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.modpacks.Constants;
import sh.siava.pixelxpert.modpacks.XPLauncher;
import sh.siava.pixelxpert.modpacks.XposedModPack;

@SuppressWarnings("RedundantThrows")
public class EasyUnlock extends XposedModPack {
	private static final String listenPackage = Constants.SYSTEM_UI_PACKAGE;

	private int expectedPassLen = -1;
	private boolean easyUnlockEnabled = false;

	private int lastPassLen = 0;
	private static boolean WakeUpToSecurityInput = false;

	public EasyUnlock(Context context) {
		super(context);
	}

	@Override
	public void updatePrefs(String... Key) {
		easyUnlockEnabled = Xprefs.getBoolean("easyUnlockEnabled", false);
		expectedPassLen = Xprefs.getInt("expectedPassLen", -1);
		WakeUpToSecurityInput = Xprefs.getBoolean("WakeUpToSecurityInput", false);
	}

	@Override
	public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals(listenPackage)) return;

		Class<?> KeyguardAbsKeyInputViewControllerClass = findClass("com.android.keyguard.KeyguardAbsKeyInputViewController", lpparam.classLoader);
		Class<?> LockscreenCredentialClass = findClass("com.android.internal.widget.LockscreenCredential", lpparam.classLoader);
		Class<?> StatusBarKeyguardViewManagerClass = findClass("com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager", lpparam.classLoader);

		boolean pre13QPR3 = findFieldIfExists(StatusBarKeyguardViewManagerClass, "mBouncer") != null;

		hookAllMethods(StatusBarKeyguardViewManagerClass, "onDozingChanged", new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				if(WakeUpToSecurityInput && param.args[0].equals(false) && (!getBooleanField(getObjectField(param.thisObject, "mKeyguardStateController"), "mCanDismissLockScreen")))//waking up
				{
					if(pre13QPR3) {
						callMethod(getObjectField(param.thisObject, "mBouncer"), "show", true, true);
					}
					else
					{
						callMethod(param.thisObject, "showPrimaryBouncer", true);
					}
				}
			}
		});

		hookAllMethods(KeyguardAbsKeyInputViewControllerClass, "onUserInput", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				if (!easyUnlockEnabled) return;

				int passwordLen = (int) callMethod(getObjectField(getObjectField(param.thisObject, "mPasswordEntry"), "mText"), "length");

				if (passwordLen == expectedPassLen && passwordLen > lastPassLen) {
					new Thread(() -> {
						try { //don't crash systemUI if failed
							int userId;
							try { //14 QPR3 beta 2.1
								userId = (int) callMethod(
										getObjectField(
												getObjectField(param.thisObject, "mKeyguardUpdateMonitor"),
												"mSelectedUserInteractor")
										, "getSelectedUserId");
							}
							catch (Throwable ignored)
							{ //14 QPR3 beta 2 and older
								userId = (int) getObjectField(getObjectField(param.thisObject, "mKeyguardUpdateMonitor"), "sCurrentUser");
							}

							String methodName = param.thisObject.getClass().getName().contains("Password") ? "createPassword" : "createPin";

							Object password = callStaticMethod(LockscreenCredentialClass, methodName, getObjectField(getObjectField(param.thisObject, "mPasswordEntry"), "mText").toString());

							boolean accepted = (boolean) callMethod(
									getObjectField(param.thisObject, "mLockPatternUtils"),
									"checkCredential",
									password,
									userId,
									null /* callback */);

							if (accepted) {
								View mView = (View) getObjectField(param.thisObject, "mView");
								int finalUserId = userId;
								mView.post(() -> {
									try { //13 QPR3
										callMethod(callMethod(param.thisObject, "getKeyguardSecurityCallback"), "dismiss", finalUserId, getObjectField(param.thisObject, "mSecurityMode"));
										return;
									} catch (Throwable ignored) {
									}

									try { //Pre 13QPR3
										callMethod(callMethod(param.thisObject, "getKeyguardSecurityCallback"), "dismiss", finalUserId, true /* sucessful */, getObjectField(param.thisObject, "mSecurityMode"));
									} catch (Throwable ignored) { //PRE-Pre 13QPR3
										callMethod(callMethod(param.thisObject, "getKeyguardSecurityCallback"), "dismiss", finalUserId, true /* sucessful */);
									}
								});
							}
						} catch (Throwable ignored){}
					}).start();
				}
				lastPassLen = passwordLen;
			}
		});

		hookAllMethods(KeyguardAbsKeyInputViewControllerClass, "onPasswordChecked", new XC_MethodHook() {
			@SuppressLint("ApplySharedPref")
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				if (!easyUnlockEnabled) return;

				boolean successful = (boolean) param.args[2];

				if (successful) {
					expectedPassLen = lastPassLen;
					Xprefs.edit().putInt("expectedPassLen", expectedPassLen).commit();
				}
			}
		});
	}

	@Override
	public boolean listensTo(String packageName) {
		return listenPackage.equals(packageName) && !XPLauncher.isChildProcess;
	}
}
