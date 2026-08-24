package __PACKAGE__.timeaccessibility;

interface ITimeShizukuService {
  String applyTime(long targetMillis);
  String setAutomaticTime(boolean enabled);
  String listOpenApps();
  String inspectApp(String packageName, String returnPackage);
  void destroy();
}
