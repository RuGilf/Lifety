import SwiftUI
import LifetyShared
import PhotosUI
import ImageIO
import UniformTypeIdentifiers

@main
struct LifetyIosApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("lifety.appearance.theme") private var themeId = "lifety"
    init() {
        IosPlannerServices.shared.companion = PlannerCompanion.shared
        IosServices.shared.arcNavigation = NativeArcNavigationFactory()
        IosServices.shared.personPhotos = NativePersonPhotoPicker()
        IosServices.shared.credentials = KeychainStore()
        IosServices.shared.health = AppleHealthSource()
        IosServices.shared.sectionLocks = SectionLockStorage()
        IosServices.shared.healthMetrics = AppleMetricSource()
    }
    private var background: UIColor { IosThemeColorsKt.iosThemeBackground(themeId: themeId) }
    var body: some Scene {
        WindowGroup {
            ZStack {
                // Fill the host's safe areas as well as the Compose content.
                Color(uiColor: background).ignoresSafeArea()
                LifetyRoot(background: background).ignoresSafeArea()
            }
            .onOpenURL { PlannerCompanion.shared.open($0) }
            .onChange(of: scenePhase) { _, phase in if phase == .active { PlannerCompanion.shared.refresh() } }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in PlannerCompanion.shared.refresh() }
            .preferredColorScheme(LifetyPalette.companion.fromId(id: themeId).palette.isDark ? .dark : .light)
        }
    }
}

private struct LifetyRoot: UIViewControllerRepresentable {
    var background: UIColor
    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController()
        controller.view.backgroundColor = background
        return controller
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {
        controller.view.backgroundColor = background
    }
}

// Native SwiftUI navigation, backed by the same KMP routes and section guards.
private final class NativeArcNavigationFactory: IosArcNavigationFactory {
    func create(events: IosArcNavigationEvents) -> IosArcNavigationSession {
        MainActor.assumeIsolated { NativeArcNavigationSession(events: events) }
    }
}

@MainActor
private final class NativeArcNavigationSession: NSObject, @preconcurrency IosArcNavigationSession {
    private let model: ArcNavigationModel
    private let host: UIHostingController<ArcNavigationView>
    var viewController: UIViewController { host }

    init(events: IosArcNavigationEvents) {
        let model = ArcNavigationModel(events: events)
        self.model = model
        self.host = UIHostingController(rootView: ArcNavigationView(model: model))
        super.init()
        host.view.backgroundColor = .clear
        host.view.isOpaque = false
    }
    func update(items: [IosArcItem], selected: String, accent: Int64, text: Int64, dark: Bool, safeBottom: Double) {
        if model.items.map(\.route) != items.map(\.route) { model.items = items }
        if model.selected != selected { model.selected = selected; model.setExpanded(false) }
        if model.accentValue != accent { model.accentValue = accent; model.accent = Color(uiColor: arcColor(accent)) }
        if model.textValue != text { model.textValue = text; model.text = Color(uiColor: arcColor(text)) }
        if model.safeBottom != safeBottom { model.safeBottom = safeBottom }
        let style: UIUserInterfaceStyle = dark ? .dark : .light
        if host.overrideUserInterfaceStyle != style { host.overrideUserInterfaceStyle = style }
    }
    func close() { model.events = nil }
}

private func arcColor(_ argb: Int64) -> UIColor {
    UIColor(red: CGFloat((argb >> 16) & 255) / 255, green: CGFloat((argb >> 8) & 255) / 255,
            blue: CGFloat(argb & 255) / 255, alpha: CGFloat((argb >> 24) & 255) / 255)
}

@MainActor
private final class ArcNavigationModel: ObservableObject {
    @Published var items: [IosArcItem] = []
    @Published var selected = "dashboard"
    @Published var expanded = false
    @Published var accent: Color = .accentColor
    @Published var text: Color = .primary
    @Published var safeBottom = 0.0
    var accentValue: Int64 = .min
    var textValue: Int64 = .min
    var events: IosArcNavigationEvents?
    init(events: IosArcNavigationEvents) { self.events = events }
    func setExpanded(_ expanded: Bool) {
        guard self.expanded != expanded else { return }
        events?.onExpanded(expanded: expanded)
        let animation: Animation? = UIAccessibility.isReduceMotionEnabled ? nil : .spring(response: 0.3, dampingFraction: 0.82)
        withAnimation(animation) { self.expanded = expanded }
    }
    func select(_ route: String) { setExpanded(false); events?.onSelect(route: route) }
}

private struct ArcNavigationView: View {
    @ObservedObject var model: ArcNavigationModel
    var body: some View {
        GeometryReader { geometry in
            let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height - model.safeBottom - 36)
            ZStack {
                if model.expanded {
                    Color.clear.contentShape(Rectangle()).onTapGesture { model.setExpanded(false) }
                }
                GlassEffectContainer(spacing: 12) {
                    ZStack {
                        if model.expanded {
                        ForEach(Array(model.items.enumerated()), id: \.element.route) { index, item in
                            let angle = model.items.count == 1 ? -Double.pi / 2 : (-160 + 140 * Double(index) / Double(model.items.count - 1)) * .pi / 180
                            let target = CGPoint(x: center.x + cos(angle) * 116, y: center.y + sin(angle) * 116)
                            Button { model.select(item.route) } label: {
                                VStack(spacing: 4) {
                                    Image(systemName: item.symbol).font(.system(size: 21, weight: .medium))
                                    Text(item.title).font(.system(size: 10, weight: .medium)).lineLimit(1).minimumScaleFactor(0.75)
                                }
                                .foregroundStyle(item.route == model.selected ? model.accent : model.text)
                                .frame(width: 66, height: 66)
                            }
                            .buttonStyle(.plain)
                            .glassEffect(.regular.interactive(), in: .circle)
                            .accessibilityLabel(item.title)
                            .accessibilityAddTraits(item.route == model.selected ? .isSelected : [])
                            .position(target)
                            .transition(.offset(x: center.x - target.x, y: center.y - target.y).combined(with: .opacity))
                        }
                        }
                        Button { model.setExpanded(!model.expanded) } label: {
                            Image(systemName: model.expanded ? "xmark" : (model.items.first { $0.route == model.selected }?.symbol ?? "square.grid.2x2"))
                                .font(.system(size: 23, weight: .medium)).foregroundStyle(model.accent)
                                .frame(width: 88, height: 50)
                        }
                        .buttonStyle(.plain)
                        .glassEffect(.regular.interactive(), in: .capsule)
                        .highPriorityGesture(LongPressGesture(minimumDuration: 0.25).onEnded { _ in model.setExpanded(true) })
                        .accessibilityLabel(model.expanded ? "Закрыть меню разделов" : "Открыть меню разделов")
                        .accessibilityHint("Удерживайте для открытия дугового меню")
                        .position(center)
                    }
                }
            }
            .accessibilityAction(.escape) { model.setExpanded(false) }
        }
        .ignoresSafeArea()
    }
}

// A single selected photo is normalized off the main thread; only a small JPEG is retained.
private final class NativePersonPhotoPicker: IosPersonPhotoPicker {
    func pick(presenter: UIViewController, completion: @escaping (String?, String?) -> Void) -> IosPersonPhotoRequest {
        MainActor.assumeIsolated {
            let request = NativePersonPhotoRequest(completion: completion)
            request.start(from: presenter)
            return request
        }
    }
}

@MainActor
private final class NativePersonPhotoRequest: NSObject, PHPickerViewControllerDelegate, UIAdaptivePresentationControllerDelegate, @preconcurrency IosPersonPhotoRequest {
    private var completion: ((String?, String?) -> Void)?
    private var picker: PHPickerViewController?
    private var progress: Progress?
    private var cancelled = false
    private var pickingEnded = false
    init(completion: @escaping (String?, String?) -> Void) { self.completion = completion }

    func start(from host: UIViewController) {
        var presenter = host
        while let presented = presenter.presentedViewController { presenter = presented }
        guard presenter.viewIfLoaded?.window != nil, !presenter.isBeingDismissed else {
            finish(nil, "Не удалось открыть фотографии. Попробуйте ещё раз."); return
        }
        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = 1
        configuration.preferredAssetRepresentationMode = .compatible
        let picker = PHPickerViewController(configuration: configuration)
        self.picker = picker
        picker.delegate = self
        presenter.present(picker, animated: true)
        picker.presentationController?.delegate = self
    }
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        picker = nil
        if !pickingEnded { finish(nil, nil) }
    }
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        pickingEnded = true
        picker.dismiss(animated: true)
        self.picker = nil
        guard let provider = results.first?.itemProvider else { finish(nil, nil); return }
        progress = provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { [weak self] url, _ in
            // The temporary URL is valid only inside this callback.
            let photo = url.flatMap(Self.thumbnail)
            DispatchQueue.main.async { [weak self] in
                self?.finish(photo, photo == nil ? "Не удалось загрузить фото. Выберите другое изображение." : nil)
            }
        }
    }
    private func finish(_ value: String?, _ error: String?) {
        guard !cancelled, let done = completion else { return }
        completion = nil
        progress = nil
        done(value, error)
    }
    func cancel() {
        cancelled = true
        completion = nil
        progress?.cancel(); progress = nil
        picker?.delegate = nil
        picker?.dismiss(animated: false); picker = nil
    }
    nonisolated private static func thumbnail(_ url: URL) -> String? {
        return autoreleasepool(invoking: { () -> String? in
            guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
                  let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: 1024,
                    kCGImageSourceShouldCacheImmediately: true
                  ] as CFDictionary),
                  let space = CGColorSpace(name: CGColorSpace.sRGB),
                  let context = CGContext(data: nil, width: 512, height: 512, bitsPerComponent: 8, bytesPerRow: 0,
                    space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
            context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
            context.fill(CGRect(x: 0, y: 0, width: 512, height: 512))
            let scale = 512 / CGFloat(min(image.width, image.height))
            let width = CGFloat(image.width) * scale, height = CGFloat(image.height) * scale
            context.interpolationQuality = .high
            context.draw(image, in: CGRect(x: (512 - width) / 2, y: (512 - height) / 2, width: width, height: height))
            guard let square = context.makeImage() else { return nil }
            for quality in [0.82, 0.65, 0.45] {
                let data = NSMutableData()
                guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
                CGImageDestinationAddImage(destination, square, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
                guard CGImageDestinationFinalize(destination) else { return nil }
                if data.length <= 180_000 { return (data as Data).base64EncodedString() }
            }
            return nil
        })
    }
}
