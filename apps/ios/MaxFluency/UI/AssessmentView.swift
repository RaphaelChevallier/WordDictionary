import SwiftUI

struct AssessmentView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                AppSectionTitle(title: model.text("assessment_title"))
                Text(model.text("assessment_note")).readingFont(14)
                if model.assessment.isEmpty { ProgressView(model.text("loading_dictionary")) }
                else if model.assessmentIndex >= model.assessment.count {
                    Text(model.format("assessment_result", model.assessmentCorrect, model.assessment.count)).readingFont()
                    if let first = model.assessment.first { Text(model.format("assessment_saved", first.language.nativeName)).readingFont(14) }
                    Button(model.text("done")) { dismiss() }.wordActionTarget()
                } else {
                    let question = model.assessment[model.assessmentIndex]
                    Text(model.format("assessment_progress", model.assessmentIndex + 1, model.assessment.count)).readingFont(14)
                    Text(model.text("assessment_question")).readingFont(18, weight: .medium)
                    if let sense = question.senses.first { Text(sense.definition).readingFont() }
                    ForEach(choices(question)) { choice in
                        Button { model.answer(choice.id) } label: {
                            Text(choice.word).readingFont().frame(maxWidth: .infinity, minHeight: 48)
                        }.buttonStyle(.bordered).disabled(model.answering)
                    }
                    Button(model.text("assessment_skip")) { model.answer(nil) }.wordActionTarget().disabled(model.answering)
                }
            }.padding(24).frame(maxWidth: .infinity, alignment: .leading)
        }.accessibilityIdentifier("assessment-screen")
    }
    private func choices(_ question: WordEntry) -> [WordEntry] {
        let wrong = model.assessment.filter {
            $0.id != question.id && !question.related.contains($0.word) && !$0.related.contains(question.word) &&
                $0.senses.first?.definition != question.senses.first?.definition
        }.prefix(2)
        return (Array(wrong) + [question]).sorted { SearchKeys.javaHash("\(question.id):\($0.id)") < SearchKeys.javaHash("\(question.id):\($1.id)") }
    }
}
