import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const DelayedVoiceMonitorApp());
}

class DelayedVoiceMonitorApp extends StatelessWidget {
  const DelayedVoiceMonitorApp({super.key});

  @override
  Widget build(BuildContext context) {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: const Color(0xFF7C4DFF),
      brightness: Brightness.dark,
    );

    return MaterialApp(
      title: 'Delayed Voice Monitor',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        colorScheme: colorScheme,
        scaffoldBackgroundColor: const Color(0xFF080A12),
        sliderTheme: SliderThemeData(
          trackHeight: 8,
          thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 13),
          overlayShape: const RoundSliderOverlayShape(overlayRadius: 26),
          activeTrackColor: colorScheme.primary,
          inactiveTrackColor: colorScheme.surfaceContainerHighest,
        ),
      ),
      home: const HomePage(),
    );
  }
}

class AudioBridge {
  static const MethodChannel _channel = MethodChannel('delayed_audio/control');

  static Future<PermissionState> requestPermissions() async {
    final raw = await _channel.invokeMapMethod<String, dynamic>('requestPermissions');
    return PermissionState.fromMap(raw ?? const <String, dynamic>{});
  }

  static Future<PermissionState> checkPermissions() async {
    final raw = await _channel.invokeMapMethod<String, dynamic>('checkPermissions');
    return PermissionState.fromMap(raw ?? const <String, dynamic>{});
  }

  static Future<void> start(int latencyMs) async {
    await _channel.invokeMethod<void>('start', <String, dynamic>{'latencyMs': latencyMs});
  }

  static Future<void> stop() async {
    await _channel.invokeMethod<void>('stop');
  }

  static Future<void> setLatency(int latencyMs) async {
    await _channel.invokeMethod<void>('setLatency', <String, dynamic>{'latencyMs': latencyMs});
  }

  static Future<AudioStatus> getStatus() async {
    final raw = await _channel.invokeMapMethod<String, dynamic>('getStatus');
    return AudioStatus.fromMap(raw ?? const <String, dynamic>{});
  }

  static Future<List<AudioRoute>> getRoutes() async {
    final raw = await _channel.invokeListMethod<dynamic>('getRoutes');
    return (raw ?? const <dynamic>[])
        .whereType<Map<dynamic, dynamic>>()
        .map((item) => AudioRoute.fromMap(item.cast<String, dynamic>()))
        .toList(growable: false);
  }

  static Future<void> selectRoute(int? id) async {
    await _channel.invokeMethod<void>('selectRoute', <String, dynamic>{'id': id});
  }
}

class PermissionState {
  const PermissionState({
    required this.microphone,
    required this.bluetooth,
    required this.notifications,
  });

  final bool microphone;
  final bool bluetooth;
  final bool notifications;

  bool get readyForAudio => microphone;

  factory PermissionState.fromMap(Map<String, dynamic> map) => PermissionState(
        microphone: map['microphone'] == true,
        bluetooth: map['bluetooth'] == true,
        notifications: map['notifications'] == true,
      );
}

class AudioStatus {
  const AudioStatus({
    required this.running,
    required this.latencyMs,
    required this.level,
    required this.sampleRate,
    required this.underruns,
    required this.error,
  });

  final bool running;
  final int latencyMs;
  final double level;
  final int sampleRate;
  final int underruns;
  final String error;

  factory AudioStatus.fromMap(Map<String, dynamic> map) => AudioStatus(
        running: map['running'] == true,
        latencyMs: (map['latencyMs'] as num?)?.round() ?? 500,
        level: ((map['level'] as num?)?.toDouble() ?? 0).clamp(0, 1).toDouble(),
        sampleRate: (map['sampleRate'] as num?)?.round() ?? 0,
        underruns: (map['underruns'] as num?)?.round() ?? 0,
        error: map['error']?.toString() ?? '',
      );
}

class AudioRoute {
  const AudioRoute({
    required this.id,
    required this.name,
    required this.type,
    required this.selected,
  });

  final int id;
  final String name;
  final String type;
  final bool selected;

  factory AudioRoute.fromMap(Map<String, dynamic> map) => AudioRoute(
        id: (map['id'] as num?)?.round() ?? -1,
        name: map['name']?.toString() ?? 'Sortie audio',
        type: map['type']?.toString() ?? 'unknown',
        selected: map['selected'] == true,
      );
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  static const int _minLatencyMs = 0;
  static const int _maxLatencyMs = 5000;

  int _latencyMs = 500;
  bool _busy = false;
  AudioStatus _status = const AudioStatus(
    running: false,
    latencyMs: 500,
    level: 0,
    sampleRate: 0,
    underruns: 0,
    error: '',
  );
  PermissionState _permissions = const PermissionState(
    microphone: false,
    bluetooth: false,
    notifications: false,
  );
  List<AudioRoute> _routes = const <AudioRoute>[];
  int? _selectedRouteId;
  Timer? _timer;
  String? _message;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _bootstrap();
    _timer = Timer.periodic(const Duration(milliseconds: 250), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _bootstrap();
    }
  }

  Future<void> _bootstrap() async {
    await _guard(() async {
      _permissions = await AudioBridge.checkPermissions();
      _routes = await AudioBridge.getRoutes();
      _status = await AudioBridge.getStatus();
      _latencyMs = _status.latencyMs.clamp(_minLatencyMs, _maxLatencyMs).toInt();
      _selectedRouteId = _routes.where((r) => r.selected).firstOrNull?.id;
    }, showBusy: false);
  }

  Future<void> _refresh() async {
    if (!mounted) return;
    try {
      final status = await AudioBridge.getStatus();
      final routes = await AudioBridge.getRoutes();
      if (!mounted) return;
      setState(() {
        _status = status;
        _routes = routes;
        _selectedRouteId = routes.where((r) => r.selected).firstOrNull?.id ?? _selectedRouteId;
      });
    } catch (_) {
      // Ignore periodic refresh errors; explicit actions show messages.
    }
  }

  Future<void> _guard(Future<void> Function() action, {bool showBusy = true}) async {
    if (_busy && showBusy) return;
    if (showBusy) setState(() => _busy = true);
    try {
      await action();
      if (mounted) setState(() => _message = null);
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() => _message = e.message ?? e.code);
      }
    } catch (e) {
      if (mounted) {
        setState(() => _message = e.toString());
      }
    } finally {
      if (mounted) {
        if (showBusy) {
          setState(() => _busy = false);
        } else {
          setState(() {});
        }
      }
    }
  }

  Future<void> _start() async {
    await _guard(() async {
      _permissions = await AudioBridge.requestPermissions();
      if (!_permissions.readyForAudio) {
        throw PlatformException(
          code: 'PERMISSION_DENIED',
          message: 'La permission micro est obligatoire pour démarrer.',
        );
      }
      await AudioBridge.start(_latencyMs);
      await _refresh();
    });
  }

  Future<void> _stop() async {
    await _guard(() async {
      await AudioBridge.stop();
      await _refresh();
    });
  }

  Future<void> _updateLatency(int value) async {
    setState(() => _latencyMs = value);
    await AudioBridge.setLatency(value);
  }

  Future<void> _selectRoute(int? id) async {
    await _guard(() async {
      _selectedRouteId = id;
      await AudioBridge.selectRoute(id);
      await _refresh();
    }, showBusy: false);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final running = _status.running;

    return Scaffold(
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: <Color>[Color(0xFF10142A), Color(0xFF080A12), Color(0xFF181022)],
          ),
        ),
        child: SafeArea(
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: <Widget>[
              _Header(status: _status),
              const SizedBox(height: 22),
              _LatencyCard(
                latencyMs: _latencyMs,
                min: _minLatencyMs,
                max: _maxLatencyMs,
                onChanged: (value) => setState(() => _latencyMs = value.round()),
                onChangeEnd: (value) => _updateLatency(value.round()),
              ),
              const SizedBox(height: 18),
              _LevelCard(level: _status.level, sampleRate: _status.sampleRate),
              const SizedBox(height: 18),
              _RouteCard(
                routes: _routes,
                selectedId: _selectedRouteId,
                onChanged: _selectRoute,
              ),
              const SizedBox(height: 18),
              AnimatedSwitcher(
                duration: const Duration(milliseconds: 250),
                child: _message == null && _status.error.isEmpty
                    ? const SizedBox.shrink()
                    : Card(
                        key: ValueKey<String>(_message ?? _status.error),
                        color: theme.colorScheme.errorContainer.withOpacity(0.85),
                        child: Padding(
                          padding: const EdgeInsets.all(14),
                          child: Text(
                            _message ?? _status.error,
                            style: TextStyle(color: theme.colorScheme.onErrorContainer),
                          ),
                        ),
                      ),
              ),
              const SizedBox(height: 16),
              Row(
                children: <Widget>[
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: _busy || running ? null : _start,
                      icon: _busy && !running
                          ? const SizedBox.square(
                              dimension: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.play_arrow_rounded),
                      label: const Text('START'),
                      style: FilledButton.styleFrom(
                        minimumSize: const Size.fromHeight(62),
                        textStyle: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                      ),
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: FilledButton.tonalIcon(
                      onPressed: _busy || !running ? null : _stop,
                      icon: const Icon(Icons.stop_rounded),
                      label: const Text('STOP'),
                      style: FilledButton.styleFrom(
                        minimumSize: const Size.fromHeight(62),
                        textStyle: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 18),
              _InfoStrip(status: _status, permissions: _permissions),
            ],
          ),
        ),
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.status});

  final AudioStatus status;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      children: <Widget>[
        Container(
          width: 58,
          height: 58,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(18),
            gradient: LinearGradient(
              colors: <Color>[theme.colorScheme.primary, theme.colorScheme.tertiary],
            ),
          ),
          child: const Icon(Icons.graphic_eq_rounded, size: 34, color: Colors.white),
        ),
        const SizedBox(width: 14),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text('Delayed Voice Monitor', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 4),
              Row(
                children: <Widget>[
                  _PulseDot(active: status.running),
                  const SizedBox(width: 8),
                  Text(status.running ? 'Audio actif' : 'En attente', style: theme.textTheme.bodyMedium),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _LatencyCard extends StatelessWidget {
  const _LatencyCard({
    required this.latencyMs,
    required this.min,
    required this.max,
    required this.onChanged,
    required this.onChangeEnd,
  });

  final int latencyMs;
  final int min;
  final int max;
  final ValueChanged<double> onChanged;
  final ValueChanged<double> onChangeEnd;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return _GlassCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text('Latence artificielle', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
          const SizedBox(height: 10),
          Center(
            child: AnimatedSwitcher(
              duration: const Duration(milliseconds: 180),
              child: Text(
                '$latencyMs ms',
                key: ValueKey<int>(latencyMs),
                style: theme.textTheme.displayMedium?.copyWith(fontWeight: FontWeight.w900),
              ),
            ),
          ),
          Slider(
            min: min.toDouble(),
            max: max.toDouble(),
            divisions: (max - min) ~/ 10,
            value: latencyMs.toDouble().clamp(min.toDouble(), max.toDouble()).toDouble(),
            label: '$latencyMs ms',
            onChanged: onChanged,
            onChangeEnd: onChangeEnd,
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: <Widget>[
              Text('$min ms'),
              Text('${max ~/ 1000} s'),
            ],
          ),
        ],
      ),
    );
  }
}

class _LevelCard extends StatelessWidget {
  const _LevelCard({required this.level, required this.sampleRate});

  final double level;
  final int sampleRate;

  @override
  Widget build(BuildContext context) {
    final pct = (level * 100).round();
    return _GlassCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              const Icon(Icons.mic_rounded),
              const SizedBox(width: 10),
              Text('Niveau micro', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
              const Spacer(),
              Text('$pct %'),
            ],
          ),
          const SizedBox(height: 14),
          ClipRRect(
            borderRadius: BorderRadius.circular(999),
            child: LinearProgressIndicator(minHeight: 12, value: math.max(0.02, level)),
          ),
          if (sampleRate > 0) ...<Widget>[
            const SizedBox(height: 10),
            Text('Échantillonnage : $sampleRate Hz'),
          ],
        ],
      ),
    );
  }
}

class _RouteCard extends StatelessWidget {
  const _RouteCard({required this.routes, required this.selectedId, required this.onChanged});

  final List<AudioRoute> routes;
  final int? selectedId;
  final ValueChanged<int?> onChanged;

  @override
  Widget build(BuildContext context) {
    final effectiveRoutes = routes.isEmpty
        ? const <AudioRoute>[AudioRoute(id: -1, name: 'Sortie système par défaut', type: 'default', selected: true)]
        : routes;
    final value = effectiveRoutes.any((r) => r.id == selectedId) ? selectedId : effectiveRoutes.first.id;

    return _GlassCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              const Icon(Icons.headphones_rounded),
              const SizedBox(width: 10),
              Text('Sortie audio', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
            ],
          ),
          const SizedBox(height: 12),
          DropdownButtonFormField<int>(
            value: value,
            decoration: const InputDecoration(border: OutlineInputBorder()),
            items: effectiveRoutes
                .map(
                  (route) => DropdownMenuItem<int>(
                    value: route.id,
                    child: Text('${route.name} • ${route.type}'),
                  ),
                )
                .toList(growable: false),
            onChanged: onChanged,
          ),
        ],
      ),
    );
  }
}

class _InfoStrip extends StatelessWidget {
  const _InfoStrip({required this.status, required this.permissions});

  final AudioStatus status;
  final PermissionState permissions;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: <Widget>[
        _Chip(icon: Icons.mic_rounded, label: permissions.microphone ? 'Micro autorisé' : 'Micro requis'),
        _Chip(icon: Icons.bluetooth_rounded, label: permissions.bluetooth ? 'Bluetooth OK' : 'Bluetooth limité'),
        _Chip(icon: Icons.notifications_active_rounded, label: permissions.notifications ? 'Notification OK' : 'Notification facultative'),
        if (status.underruns > 0) _Chip(icon: Icons.warning_rounded, label: '${status.underruns} sous-alim.'),
      ],
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Chip(avatar: Icon(icon, size: 18), label: Text(label));
  }
}

class _GlassCard extends StatelessWidget {
  const _GlassCard({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Card(
      elevation: 0,
      color: Colors.white.withOpacity(0.075),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(28),
        side: BorderSide(color: Colors.white.withOpacity(0.10)),
      ),
      child: Padding(padding: const EdgeInsets.all(18), child: child),
    );
  }
}

class _PulseDot extends StatefulWidget {
  const _PulseDot({required this.active});

  final bool active;

  @override
  State<_PulseDot> createState() => _PulseDotState();
}

class _PulseDotState extends State<_PulseDot> with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 900),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final color = widget.active ? Colors.greenAccent : Colors.orangeAccent;
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) {
        final scale = widget.active ? 1.0 + (_controller.value * 0.35) : 1.0;
        return Transform.scale(
          scale: scale,
          child: Container(
            width: 11,
            height: 11,
            decoration: BoxDecoration(color: color, shape: BoxShape.circle, boxShadow: <BoxShadow>[BoxShadow(color: color.withOpacity(0.6), blurRadius: 12)]),
          ),
        );
      },
    );
  }
}

extension FirstOrNullExtension<E> on Iterable<E> {
  E? get firstOrNull => isEmpty ? null : first;
}
