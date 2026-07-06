import 'package:delayed_voice_monitor/main.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('permission state parsing is robust', () {
    final state = PermissionState.fromMap(const <String, dynamic>{
      'microphone': true,
      'bluetooth': false,
      'notifications': true,
    });
    expect(state.microphone, isTrue);
    expect(state.bluetooth, isFalse);
    expect(state.notifications, isTrue);
  });
}
