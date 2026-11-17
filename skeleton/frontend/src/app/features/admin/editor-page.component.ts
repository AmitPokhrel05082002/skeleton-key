import { ChangeDetectionStrategy, Component, viewChild } from '@angular/core';
import { FormBuilderComponent } from './form-builder.component';
import { JsonEditorComponent } from './json-editor.component';
import { HasUnsavedChanges } from './unsaved-changes.guard';

/** Routed page for `editor/new` and `editor/:id`: the JSON editor with the form builder panel beside it. */
@Component({
  selector: 'sk-editor-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [JsonEditorComponent, FormBuilderComponent],
  template: `
    <div class="sk-editor-page">
      <sk-json-editor #ed />
      <aside class="sk-builder-side">
        <sk-form-builder [text]="ed.text()" (applied)="ed.setText($event)" />
      </aside>
    </div>
  `,
  styles: `
    .sk-editor-page {
      display: grid;
      grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 991px) {
      .sk-editor-page {
        grid-template-columns: 1fr;
      }
    }
  `,
})
export class EditorPageComponent implements HasUnsavedChanges {
  private readonly editor = viewChild.required(JsonEditorComponent);

  hasUnsavedChanges(): boolean {
    return this.editor().hasUnsavedChanges();
  }
}
