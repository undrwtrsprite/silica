import SwiftUI

/// View-local state without the `@State` macro. In this SDK `@State` is a macro whose implementation (SwiftUIMacros) ships only
/// inside full Xcode, so a Command Line Tools-only toolchain cannot compile it. `@StateObject` has no such dependency, so this wrapper
/// gives the same behaviour on top of it: value stored per view identity, mutations trigger redraws, `$value` yields a Binding.
@propertyWrapper
struct Local<Value>: DynamicProperty {
    private final class Box: ObservableObject {
        @Published var value: Value
        init(_ value: Value) { self.value = value }
    }

    @StateObject private var box: Box

    init(wrappedValue: Value) { _box = StateObject(wrappedValue: Box(wrappedValue)) }

    var wrappedValue: Value {
        get { box.value }
        nonmutating set { box.value = newValue }
    }

    var projectedValue: Binding<Value> {
        let b = box
        return Binding(get: { b.value }, set: { b.value = $0 })
    }
}
