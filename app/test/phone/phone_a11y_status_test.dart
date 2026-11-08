import 'package:dsh_app/phone/phone_channel.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('older native modules keep connected == ok', () {
    final status = PhoneA11yStatus.fromMap({
      'enabled': true,
      'serviceConnected': true,
    });
    expect(status.ok, isTrue);
    expect(status.healthy, isTrue);
    expect(status.stale, isFalse);
  });

  test('STALE is connected but not ok', () {
    final status = PhoneA11yStatus.fromMap({
      'enabled': true,
      'serviceConnected': true,
      'alive': true,
      'healthy': false,
      'verdict': 'stale',
      'lastEventAgeMs': 400000,
      'failures': 1,
    });
    expect(status.serviceConnected, isTrue);
    expect(status.healthy, isFalse);
    expect(status.ok, isFalse);
    expect(status.stale, isTrue);
    expect(status.verdict, 'stale');
    expect(status.lastEventAgeMs, 400000);
    expect(status.failures, 1);
  });

  test('DEAD is not alive and not ok', () {
    final status = PhoneA11yStatus.fromMap({
      'enabled': true,
      'serviceConnected': true,
      'alive': false,
      'healthy': false,
      'verdict': 'dead',
      'lastProbeAgeMs': 30000,
    });
    expect(status.alive, isFalse);
    expect(status.ok, isFalse);
    expect(status.stale, isTrue);
  });

  test('disabled is never ok', () {
    final status = PhoneA11yStatus.fromMap({
      'enabled': false,
      'serviceConnected': true,
      'healthy': true,
    });
    expect(status.ok, isFalse);
    expect(status.stale, isFalse);
  });
}
