// Wiring for the proactive kernel's Agent transport (D1).
//
// The kernel (loop/core) cannot reach the network on its own: the authenticated cookie lives in
// Dart. [ProactiveTransportHost] starts the EventChannel listener once, and answers each command
// with the connection of the *current* host, so switching hosts cannot send a request to the wrong
// engine.

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../proactive/proactive_channel.dart';
import '../state/connection_state.dart';
import '../state/hosts_state.dart';

/// The transport, bound to the app's live connection.
///
/// Kept as a provider so tests can swap it, and so nothing has to reach into `main()`.
final proactiveTransportProvider = Provider<ProactiveAgentTransport>((ref) {
  final transport = ProactiveAgentTransport(
    targetOf: () {
      final host = ref.read(activeHostProvider);
      final connection = ref.read(activeConnectionProvider).value;
      if (connection == null) return null;
      return DshConnectionTarget(connection.client.connection, isLocal: host.isLocal);
    },
  );
  ref.onDispose(transport.stop);
  return transport;
});

/// Starts the listener and keeps it alive for the app's lifetime. Returns the transport.
///
/// Idempotent: [ProactiveAgentTransport.start] ignores a second call, so a rebuild is harmless.
ProactiveAgentTransport watchProactiveTransport(WidgetRef ref) {
  final transport = ref.watch(proactiveTransportProvider);
  transport.start();
  return transport;
}
