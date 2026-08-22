package __PACKAGE__.timeaccessibility;

interface ITimeShizukuService {
  String applyTime(long targetMillis);
  String setAutomaticTime(boolean enabled);
  void destroy();
}
