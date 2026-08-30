package __PACKAGE__.timeaccessibility;

interface ITimeShizukuService {
  String applyTime(long targetMillis);
  String setAutomaticTime(boolean enabled);
  String getAutomaticTime();
  void cancelCurrentCommand();
  void destroy();
}
