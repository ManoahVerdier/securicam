import { Injectable, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap, catchError, throwError } from 'rxjs';
import { environment } from '../../environments/environment';
import { User, AuthResponse, LoginCredentials, RegisterCredentials } from '../models';

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private readonly TOKEN_KEY = 'auth_token';
  private readonly USER_KEY = 'auth_user';

  private userSignal = signal<User | null>(this.loadUser());
  private tokenSignal = signal<string | null>(this.loadToken());

  readonly user = this.userSignal.asReadonly();
  readonly isAuthenticated = computed(() => !!this.tokenSignal());
  readonly token = this.tokenSignal.asReadonly();

  constructor(
    private http: HttpClient,
    private router: Router
  ) {}

  register(credentials: RegisterCredentials): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${environment.apiUrl}/register`, credentials).pipe(
      tap(response => this.handleAuthResponse(response)),
      catchError(error => this.handleError(error))
    );
  }

  login(credentials: LoginCredentials): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${environment.apiUrl}/login`, credentials).pipe(
      tap(response => this.handleAuthResponse(response)),
      catchError(error => this.handleError(error))
    );
  }

  logout(): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`${environment.apiUrl}/logout`, {}).pipe(
      tap(() => this.clearAuth()),
      catchError(error => {
        this.clearAuth();
        return throwError(() => error);
      })
    );
  }

  getUser(): Observable<{ user: User }> {
    return this.http.get<{ user: User }>(`${environment.apiUrl}/user`).pipe(
      tap(response => {
        this.userSignal.set(response.user);
        localStorage.setItem(this.USER_KEY, JSON.stringify(response.user));
      }),
      catchError(error => this.handleError(error))
    );
  }

  getToken(): string | null {
    return this.tokenSignal();
  }

  private handleAuthResponse(response: AuthResponse): void {
    this.tokenSignal.set(response.token);
    this.userSignal.set(response.user);
    localStorage.setItem(this.TOKEN_KEY, response.token);
    localStorage.setItem(this.USER_KEY, JSON.stringify(response.user));
  }

  private clearAuth(): void {
    this.tokenSignal.set(null);
    this.userSignal.set(null);
    localStorage.removeItem(this.TOKEN_KEY);
    localStorage.removeItem(this.USER_KEY);
    this.router.navigate(['/login']);
  }

  private loadToken(): string | null {
    return localStorage.getItem(this.TOKEN_KEY);
  }

  private loadUser(): User | null {
    const userJson = localStorage.getItem(this.USER_KEY);
    if (userJson) {
      try {
        return JSON.parse(userJson);
      } catch {
        return null;
      }
    }
    return null;
  }

  private handleError(error: any): Observable<never> {
    if (error.status === 401) {
      this.clearAuth();
    }
    return throwError(() => error);
  }
}
