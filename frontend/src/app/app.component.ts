import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Language, LocalizationService } from './core/api.service';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'sgf-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, FormsModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css', changeDetection: ChangeDetectionStrategy.OnPush
})
export class AppComponent {
  readonly localization = inject(LocalizationService);
  readonly t = (key: string): string => this.localization.t(key);
  setLanguage(language: Language): void { this.localization.setLanguage(language); }
}
