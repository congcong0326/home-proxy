import { configureStore } from '@reduxjs/toolkit';
import authReducer, { getSetupStatusAsync, setupAdminAsync } from './authSlice';
import { apiService } from '../services/api';

jest.mock('../services/api', () => ({
  apiService: {
    getSetupStatus: jest.fn(),
    setupAdmin: jest.fn(),
  },
}));

describe('auth setup state', () => {
  beforeEach(() => {
    localStorage.clear();
    jest.clearAllMocks();
  });

  it('stores setup-required status from the backend', async () => {
    (apiService.getSetupStatus as jest.Mock).mockResolvedValue({ setupRequired: true });
    const store = configureStore({ reducer: { auth: authReducer } });

    await store.dispatch(getSetupStatusAsync());

    expect(store.getState().auth.setupChecked).toBe(true);
    expect(store.getState().auth.setupRequired).toBe(true);
  });

  it('authenticates after creating the first admin', async () => {
    (apiService.setupAdmin as jest.Mock).mockResolvedValue({
      token: 'setup-token',
      mustChangePassword: false,
      expiresIn: 86400,
      user: {
        id: 1,
        username: 'owner',
        roles: ['SUPER_ADMIN'],
        mustChangePassword: false,
      },
    });
    const store = configureStore({ reducer: { auth: authReducer } });

    await store.dispatch(setupAdminAsync({ username: 'owner', password: 'changeMe123' }));

    expect(localStorage.getItem('token')).toBe('setup-token');
    expect(store.getState().auth.isAuthenticated).toBe(true);
    expect(store.getState().auth.setupRequired).toBe(false);
    expect(store.getState().auth.user?.username).toBe('owner');
  });
});
